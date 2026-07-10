# DEMO-1: User Signup Endpoint — Design

- **Jira**: https://golinkup.atlassian.net/browse/DEMO-1
- **Branch**: `feat/DEMO-1-implement-signup-functionality`
- **Mode**: Autonomous pipeline (per `CLAUDE.md`) — brainstorm self-approved against the acceptance criteria below; no human sign-off pause required (session is non-interactive and the four scenarios in `.claude/sprint-context.md` are unambiguous). Open engineering choices left by the ticket are resolved explicitly in "Decisions" below.

## Context

Repo is the fresh Spring Boot 4.1 boilerplate on `main` (only `spring-boot-starter` + `spring-boot-starter-test`, no JPA/DB/Security/quality-gate plugins wired up). A sibling ticket, DEMO-2 (login), was implemented on an **unmerged** branch (`feat/DEMO-2-implement-login-funcitonality`, open PR #1) that already built a `users` table, `User` entity, JPA/Security/JWT plumbing, and the checkstyle/spotbugs/jacoco plugins. Because that branch isn't in `main` yet, DEMO-1 must not depend on it — building on top of an unmerged branch would drag unrelated login commits into this PR's diff, violating CLAUDE.md §7 (scope). This ticket therefore lays its own minimal foundation, independently. Reconciling the two competing `V1__create_users_table.sql` migrations is a normal merge-time conflict for whichever PR merges second — out of scope for this design.

## Decisions (resolving ticket's open choices)

1. **Target table — `users`.** Ticket allows `users` or `employee_info`; `users` is the natural fit for an auth signup domain and matches the sibling login ticket's naming, minimizing future reconciliation pain.
2. **Public user ID shape — `usr_{id}`.** The ticket's example response (`"userId": "usr_10293"`) implies a string ID distinct from the DB primary key. Derive it deterministically from the internal `BIGSERIAL` id (`"usr_" + id`) rather than introducing a separate UUID column — no requirement calls for opacity/unguessability, just a stable external identifier.
3. **Error envelope — RFC 7807 `ProblemDetail` + ticket's extension fields.** CLAUDE.md mandates `application/problem+json` project-wide; the ticket's literal `{errorCode, message}` JSON is the *minimum* shape, not a replacement. Use Spring's `ProblemDetail` with standard `status`/`title`/`detail`, plus `errorCode` and `message` extension properties (409 case). For 400 validation failures (AC15 — "explicitly listing the missing constraints"), add a `violations` extension: a list of `{field, message}` entries from Bean Validation.
4. **Rate limiting — in-process filter, not a real gateway.** No API gateway exists in this boilerplate; ticket says "backend gateway layer" but the only place to enforce this today is the application itself. Implement a `OncePerRequestFilter` scoped to `POST /api/v1/auth/signup` with an in-memory fixed-window counter keyed by client IP (`X-Forwarded-For` first hop, falling back to `request.getRemoteAddr()`), reset every 60s, threshold 10. **Known limitation**: single-instance only (no shared store like Redis) — acceptable for this scope, documented rather than silently assumed away; a multi-instance deployment would need a shared counter (e.g. Redis) which is out of scope for this ticket.
5. **Password complexity — enforced via `@Pattern` on the DTO**, not a custom validator class: `^(?=.*[A-Z])(?=.*[0-9])(?=.*[^A-Za-z0-9]).{8,}$` (≥8 chars, ≥1 uppercase, ≥1 digit, ≥1 special char), matching AC14 verbatim. Kept as a single bean-validation annotation rather than a new abstraction since there's exactly one rule set and no reuse target yet.

## Architecture

```
SignupController (Controller)
  → SignupService (Service)         -- duplicate check, hashing, persistence
      → UserRepository (Spring Data JPA)
      → PasswordEncoder (BCrypt)
  ← GlobalExceptionHandler (@RestControllerAdvice) -- RFC7807 mapping
SignupRateLimitFilter (Filter, registered ahead of dispatch) -- 429 guard
```

- **`User`** entity (`com.superpowers.test.user`): `id` (`BIGSERIAL`), `email` (unique, case-insensitive index), `passwordHash`, `name`, `createdAt`, `updatedAt` (`@CreationTimestamp` / `@UpdateTimestamp`). No `role`/`failedAttempts`/`lockedUntil` — those are login-ticket concerns, not needed here (YAGNI); adding them speculatively would be scope creep this ticket doesn't need.
- **Flyway** `V1__create_users_table.sql` — new migration, no existing migrations touched (CLAUDE.md §7). Unique index on `LOWER(email)`.
- **`SignupRequest`** DTO: `@NotBlank` on `name`; `@NotBlank` + `@Email` + `@Pattern` (structural regex) on `email`; `@NotBlank` + `@Pattern` (complexity regex, decision 5) on `password`. Bean Validation trips before any service/repository call → AC11–15.
- **`SignupService.register(SignupRequest)`**:
  1. Look up user by lower-cased email; if present → throw `EmailAlreadyExistsException` (409, `EMAIL_ALREADY_EXISTS`) — abort before any write (AC6–9).
  2. Hash password via injected `PasswordEncoder` (`BCryptPasswordEncoder` bean).
  3. Persist new `User` (timestamps auto-populated by Hibernate).
  4. Catch `DataIntegrityViolationException` from the unique index as a race-condition fallback → same `EmailAlreadyExistsException` (409).
  5. Return `usr_{id}` + success payload → 201.
- **`SignupController`**: `POST /api/v1/auth/signup`, `@Valid @RequestBody SignupRequest`, returns 201 with the ticket's success payload shape.
- **`SignupRateLimitFilter`**: intercepts only the signup path; on limit exceeded, writes a 429 `application/problem+json` body directly (before the request reaches Spring MVC dispatch) and short-circuits.
- **`GlobalExceptionHandler`**: maps `MethodArgumentNotValidException` → 400 (`violations` list), `EmailAlreadyExistsException` → 409 (`EMAIL_ALREADY_EXISTS`).

## Out of scope (explicitly)

- Login/authentication (JWT issuance, session handling) — separate ticket (DEMO-2), not touched here.
- Distributed/shared rate-limit storage — single-instance in-memory counter only (decision 4).
- Email verification / account activation flow — not in the ticket's acceptance criteria.
- `role`/authorization fields on `User` — no RBAC requirement in this ticket.

## Testing

- **Unit** (JUnit5 + Mockito + AssertJ): `SignupServiceTest` covers hashing-before-persist, duplicate-email rejection (pre-check and race-condition fallback), timestamp population. `SignupRequestValidationTest` covers null/blank/malformed email, weak-password boundary cases. `SignupRateLimitFilterTest` covers the 11th request in a window returning 429 and the window reset.
- **Integration** (`@SpringBootTest` + Testcontainers Postgres — Docker confirmed available): `SignupControllerIntegrationTest` drives real HTTP — 201 with generated `userId` and persisted hashed password, 409 on duplicate email, 400 for missing/blank/malformed fields, 429 after 10 rapid requests from the same IP.
- **Coverage**: JaCoCo added to `pom.xml`, target ≥80% on new code per CLAUDE.md §5.

## Quality gate plugins added to `pom.xml`

None exist today (fresh boilerplate). Adding: `maven-checkstyle-plugin`, `spotbugs-maven-plugin`, `jacoco-maven-plugin`. Runtime deps: `spring-boot-starter-security` (for `BCryptPasswordEncoder`), `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `spring-boot-starter-web`, `flyway-core`, `postgresql` driver. Test deps: `spring-boot-testcontainers`, `testcontainers-postgresql`, `testcontainers-junit-jupiter`.
