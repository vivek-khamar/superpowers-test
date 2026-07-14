# DEMO-2: Stateless JWT Login Endpoint — Design

- **Jira**: https://golinkup.atlassian.net/browse/DEMO-2
- **Branch**: `feat/DEMO-2-implement-login-funcitonality`
- **Mode**: Autonomous pipeline (per `CLAUDE.md`) — brainstorm self-approved against acceptance criteria below, no human sign-off pause required. Two open-ended choices left by the ticket are resolved explicitly in "Decisions" since neither is a genuine ambiguity (the ticket itself names both options as acceptable).

## Context

Repo is a fresh Spring Boot 4.1 boilerplate: only `spring-boot-starter` + `spring-boot-starter-test`. No Spring Security, JPA, database, Flyway, JWT library, or quality-gate Maven plugins (checkstyle/spotbugs/jacoco) are wired up yet. This ticket is the first vertical slice, so it also lays the minimal foundation (User domain, security config, JWT plumbing, quality-gate plugins) needed for the login endpoint — nothing beyond that.

## Decisions (resolving ticket's open choices)

1. **Lockout storage — DB flag, not Redis.** Ticket explicitly allows either. Project has no Redis dependency/infra; adding one would be a new infra commitment out of proportion to one endpoint. Use two columns on `users` (`failed_attempts`, `locked_until`) updated transactionally. Keyed by email (the "user target identity"), matching AC16.
2. **Error body shape — RFC 7807 `ProblemDetail` + ticket's extension fields.** CLAUDE.md mandates `application/problem+json` project-wide; the ticket's literal JSON (`errorCode`, `message`) is the *minimum* shape, not a replacement for the standard envelope. Use Spring's `ProblemDetail` with `status`, `title`, `detail` populated normally, plus `errorCode` and `message` set as extension properties so both contracts are satisfied simultaneously.

## Architecture

```
AuthController (Controller)
  → AuthService (Service)      -- credential check, lockout, JWT issuance
      → UserRepository (Spring Data JPA)
      → PasswordEncoder (BCrypt)
      → JwtService              -- sign/parse tokens
  ← GlobalExceptionHandler (@RestControllerAdvice) -- RFC7807 mapping
SecurityConfig -- stateless filter chain, permits /api/v1/auth/login
```

- **`User`** entity: `id`, `email` (unique, case-insensitive lookup), `passwordHash`, `name`, `roles` (comma-delimited or `@ElementCollection`; kept simple as a single `role` string — no multi-role requirement in AC), `failedAttempts` (int, default 0), `lockedUntil` (nullable `Instant`).
- **Flyway** `V1__create_users_table.sql` creates the table (new migration file — no existing migrations are touched, per CLAUDE.md §7).
- **`LoginRequest`** DTO: `@NotBlank` + `@Email` + `@Pattern` (structural regex) on `email`; `@NotBlank` on `password`. Bean Validation trips before any repository/service call → AC11–15.
- **`AuthService.login(email, rawPassword)`**:
  1. Look up user by email (case-insensitive).
  2. If found and `lockedUntil` is in the future → log masked-identity security warning, throw `AccountLockedException` → 423.
  3. Run `passwordEncoder.matches(rawPassword, user != null ? user.getPasswordHash() : DUMMY_HASH)` unconditionally — keeps timing uniform whether the account exists or not (AC10) and avoids leaking existence via lockout state.
  4. Match + user present → reset `failedAttempts`/`lockedUntil`, issue JWT, return user view → 200.
  5. No match (or no user) → if user exists, increment `failedAttempts`; at 5, set `lockedUntil = now + 15m` and log security warning with masked email. Throw `AuthenticationFailedException` → 401, generic body regardless of which sub-case occurred.
- **`JwtService`**: HS256 via `io.jsonwebtoken` (jjwt), claims `sub` (user id), `roles`, `iat`, `exp` (`exp = iat + 86400s`), secret + TTL bound via `@ConfigurationProperties(prefix = "app.jwt")`.
- **`AuthController`**: `POST /api/v1/auth/login`, `@Valid @RequestBody LoginRequest`, on success attaches `Set-Cookie: jwt=...; HttpOnly; Secure; SameSite=Strict; Max-Age=86400; Path=/` via `ResponseCookie`, body is the success payload from the ticket.
- **`GlobalExceptionHandler`**: maps `MethodArgumentNotValidException` → 400 problem+json, `AuthenticationFailedException` → 401 (`errorCode=AUTH_FAILED`), `AccountLockedException` → 423 (`errorCode=ACCOUNT_LOCKED`).
- **`SecurityConfig`**: stateless session, CSRF disabled (stateless JSON API), permits the login path, `BCryptPasswordEncoder` bean.

## Out of scope (explicitly)

- User registration/seeding endpoint — login only, per ticket. Tests create users directly via the repository.
- TLS 1.3 termination (AC23) — infra/ingress concern, not application code; noted in README, not implemented (no cert material in this environment).
- Real p95 latency load-testing (AC22) — no load-test harness added; default BCrypt cost (10) kept to stay within budget, documented as a known limitation rather than fabricated as "verified."
- Multi-role authorization — single `role` field is enough for the JWT claim shape the ticket asks for; no role-based access control elsewhere exists to test against.

## Testing

- **Unit** (JUnit5 + Mockito + AssertJ): `AuthServiceTest` covers all 5 scenarios' service-layer logic (match, no-match, unknown email, lockout trigger at 5th failure, already-locked). `JwtServiceTest` covers claim generation/parsing.
- **Integration** (`@SpringBootTest` + Testcontainers Postgres — Docker confirmed available locally): `AuthControllerIntegrationTest` drives real HTTP through the full stack — 200 with `Set-Cookie`, 401 generic body, 400 for missing/blank/malformed email, 423 after 5 failures then confirms unlock isn't required within scope.
- **Coverage**: JaCoCo added to `pom.xml`, target ≥80% on new code per CLAUDE.md §5.

## Quality gate plugins added to `pom.xml`

None exist today. Adding: `maven-checkstyle-plugin` (lean custom ruleset — no javadoc mandates, to avoid unrelated noise), `spotbugs-maven-plugin`, `jacoco-maven-plugin`. Also adding runtime deps: `spring-boot-starter-security`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `flyway-core`, `postgresql` driver, `io.jsonwebtoken:jjwt-api/impl/jackson`; test deps: `spring-boot-testcontainers`, `testcontainers-postgresql`, `testcontainers-junit-jupiter`.
