# DEMO-4: Admin User Directory Endpoint — Design

- **Jira**: https://golinkup.atlassian.net/browse/DEMO-4
- **Branch**: `feat/DEMO-4-implement-admin-dashboard`
- **Mode**: Autonomous pipeline (per `CLAUDE.md`) — brainstorm self-approved against the four scenarios in `.claude/sprint-context.md`; no human sign-off pause required (session is non-interactive and the ACs are unambiguous). Open engineering choices left by the ticket are resolved explicitly in "Decisions" below.

## Context

Repo is past DEMO-1 (signup), DEMO-2 (login), DEMO-3 (onboarding), all merged. Real base package is `com.superpowers.test` (sprint-context's `com.example.api` is stale boilerplate — following the actual code, as DEMO-3's design also noted).

Relevant existing pieces:

- `User` entity (`com.superpowers.test.user`): `id` (`Long`, `IDENTITY`), `email`, `passwordHash`, `name`, `role` (plain `String`, values `"USER"`/`"ADMIN"` — no `ROLE_` prefix stored), `status` (`UserStatus` enum), `createdAt`/`updatedAt` (`Instant`), plus onboarding fields. `UserRepository extends JpaRepository<User, Long>` with only `findByEmailIgnoreCase`.
- `JwtAuthenticationFilter` already turns the JWT's `roles` claim into a `SimpleGrantedAuthority("ROLE_" + role)` and populates `SecurityContext`. This ticket's authorization check can reuse that as-is.
- `SecurityConfig` currently has only `permitAll` for signup/login and `anyRequest().authenticated()` — no role-gated route exists yet, and no `AccessDeniedHandler` is registered (a 403 today would fall through to Spring Security's default HTML/empty response, not RFC 7807).
- `GlobalExceptionHandler` (`@RestControllerAdvice`) maps validation/domain exceptions to `ProblemDetail`. Extending it, not inventing a new mechanism.
- **No admin-provisioning path exists anywhere in the codebase** — signup always creates role `"USER"` (`SignupService`). Confirmed via repo-wide grep for `"ADMIN"` (zero hits). Creating admin accounts is not requested by any AC here, so it stays out of scope; tests seed an admin row directly via `UserRepository`.

## Decisions (resolving ticket's open choices)

1. **`id` in the response is `String.valueOf(user.getId())`, not a prefixed string like `"usr_78912"`.** The ticket's example payload shows prefixed IDs, but the real `User.id` is a plain `Long` (DEMO-1/2/3 precedent, and CLAUDE.md §7 forbids touching existing migrations to add a new ID scheme). Inventing a prefix scheme with no backing column would be cosmetic-only and contradicts YAGNI; the AC only requires a unified collection of both role types, not a specific ID format. `String` typing (not `Long`) is kept in the DTO so the contract still matches the ticket's stated JSON type.
2. **Role filter binds to a Java enum `AdminUserRole { ROLE_USER, ROLE_ADMIN }`** in the query param, decoupled from the DB's stored `"USER"`/`"ADMIN"` strings via a one-line mapping (`role.name().substring("ROLE_".length())`) in the service. This keeps the wire contract exactly as the ticket specifies (`?role=ROLE_ADMIN`) without leaking the DB's internal naming, and gives free 400 handling for garbage values (Spring's enum `Converter` throws `MethodArgumentTypeMismatchException` on an unmatched string).
3. **`MethodArgumentTypeMismatchException` → 400 `application/problem+json`, new `GlobalExceptionHandler` case.** Today an invalid query param would fall through to Spring Boot's default whitelabel error, breaking the project-wide RFC 7807 contract. `errorCode=INVALID_QUERY_PARAMETER`.
4. **Authorization enforced at the filter-chain level (`requestMatchers("/api/v1/admin/**").hasRole("ADMIN")`), not `@PreAuthorize`.** Matches the existing `SecurityConfig` style (path-matcher based, no `@EnableMethodSecurity` in the codebase yet) rather than introducing a second authorization mechanism for one endpoint.
5. **New `RestAccessDeniedHandler` (mirrors the existing `RestAuthenticationEntryPoint`) for the 403 case.** Registered via `.exceptionHandling(e -> e.authenticationEntryPoint(...).accessDeniedHandler(...))`. Writes the same `errorCode`/`message` shape as the entry point (`errorCode=ACCESS_DENIED`), keeping both auth-failure responses visually consistent.
6. **Search matches `name` OR `email`, case-insensitive, via `LOWER(field) LIKE LOWER('%term%')`.** Built with a small `Specification<User>` combined with an optional role predicate — avoids hand-concatenated JPQL strings and keeps the two filters (independently optional) composable and unit-testable.
7. **Pagination response is a hand-written envelope (`AdminUserPageResponse`), not Spring's default `Page` JSON.** Spring's `Page` serializes with a much larger shape (`pageable.sort`, `first`, `last`, `numberOfElements`, `empty`, etc.) that doesn't match the ticket's exact contract (`content` + `pageable{pageNumber,pageSize,totalElements,totalPages}`). The controller/service map the internal `Page<User>` to this shape explicitly.
8. **Default sort: `createdAt DESC`.** Not specified by any AC; newest-first is the conventional default for an admin directory view and is stable/deterministic given `createdAt` is set once at creation (`@CreationTimestamp`).
9. **New Flyway migration `V4__add_indexes_for_admin_user_search.sql`**, adding B-tree indexes on `role` and `name` (an index on `email` already exists — `ux_users_email` — from V1). This is a new file, not a change to `V1`–`V3` (CLAUDE.md §7 only forbids modifying *existing* migrations). Satisfies AC17's indexing requirement structurally; the numeric p99 <150ms target (AC18) is not something a unit/integration test can assert meaningfully in this environment, so it's addressed via indexing + bounded default page size (20) rather than a load-test artifact.
10. **No `@PageableDefault` beyond page=0/size=20 — no max page-size cap added.** Not requested by any AC; adding one would be scope creep beyond the ticket. (Noted here in case a future ticket asks for it.)

## Architecture

```
SecurityConfig                                        -- requestMatchers("/api/v1/admin/**").hasRole("ADMIN"); registers RestAccessDeniedHandler
RestAccessDeniedHandler                                -- 403 problem+json for authenticated-but-wrong-role
GlobalExceptionHandler                                 -- + MethodArgumentTypeMismatchException -> 400

AdminUserController (Controller)
  → AdminUserService (Service)                         -- builds Specification, maps Page<User> -> AdminUserPageResponse
      → UserRepository (Spring Data JPA + JpaSpecificationExecutor<User>)
```

- **`UserRepository`**: add `extends JpaSpecificationExecutor<User>` (no new query methods needed — filtering done via `Specification`).
- **`AdminUserRole`** enum (`com.superpowers.test.admin`): `ROLE_USER`, `ROLE_ADMIN`.
- **`AdminUserResponse`** record (`com.superpowers.test.admin.dto`): `id` (`String`), `name`, `email`, `role` (`String`, re-prefixed `"ROLE_" + user.getRole()`), `status` (`String`), `createdAt` (`Instant`).
- **`PageableInfo`** record: `pageNumber`, `pageSize`, `totalElements`, `totalPages`.
- **`AdminUserPageResponse`** record: `content` (`List<AdminUserResponse>`), `pageable` (`PageableInfo`).
- **`AdminUserController`**: `@GetMapping("/api/v1/admin/users")`, params `@RequestParam(defaultValue = "0") int page`, `@RequestParam(defaultValue = "20") int size`, `@RequestParam(required = false) AdminUserRole role`, `@RequestParam(required = false) String search`. Delegates to `AdminUserService.listUsers(...)`, returns 200 with `AdminUserPageResponse`.
- **`AdminUserService.listUsers(page, size, role, search)`**: builds a `Pageable` (`PageRequest.of(page, size, Sort.by("createdAt").descending())`), builds a `Specification<User>` combining an optional role-equals predicate and an optional case-insensitive name-or-email LIKE predicate, queries `userRepository.findAll(spec, pageable)`, maps the resulting `Page<User>` to `AdminUserPageResponse`.
- **`SecurityConfig`** additions: new matcher `.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")` (placed before the existing `anyRequest().authenticated()`); `.exceptionHandling(e -> e.authenticationEntryPoint(restAuthenticationEntryPoint).accessDeniedHandler(restAccessDeniedHandler))`.
- **`RestAccessDeniedHandler`** (`com.superpowers.test.auth`, mirrors `RestAuthenticationEntryPoint`): implements `AccessDeniedHandler`, writes `{"errorCode":"ACCESS_DENIED","message":"You do not have permission to access this resource."}`, HTTP 403, `application/problem+json`.
- **`GlobalExceptionHandler`** addition: `MethodArgumentTypeMismatchException` → 400, `errorCode=INVALID_QUERY_PARAMETER`.

## Out of scope (explicitly)

- Any admin-account creation/promotion endpoint — no AC requests one; tests seed admin rows directly via `UserRepository`.
- A maximum page-size cap / rate limiting on this endpoint — not requested.
- Load/perf testing to literally verify the p99 <150ms target — addressed structurally (indexes + bounded page size), not measured in CI.
- Sorting by fields other than `createdAt`, or exposing a `sort` query param — not requested by any AC.

## Testing

- **Unit** (JUnit5 + Mockito + AssertJ): `AdminUserServiceTest` — no filters (returns all, both roles unified); role filter narrows to one role; search matches name-only, email-only, and case-insensitively; combined role+search; empty result page; pagination fields map correctly (`pageNumber`/`pageSize`/`totalElements`/`totalPages`).
- **Integration** (`@SpringBootTest` + Testcontainers Postgres, `TestRestTemplate`, following `OnboardingControllerIntegrationTest`'s pattern): seed one `ROLE_USER` and one `ROLE_ADMIN` row → 200 with both in `content` for an admin caller; `?role=ROLE_ADMIN` returns only the admin row; `?search=` partial match on name/email; no `Authorization` → 401 `UNAUTHENTICATED`; valid JWT for a `USER`-role caller → 403 `ACCESS_DENIED`; invalid `?role=BOGUS` → 400 `INVALID_QUERY_PARAMETER`.
- **Coverage**: JaCoCo ≥80% line coverage on new code, per CLAUDE.md §5.

## New dependencies (`pom.xml`)

None — `JpaSpecificationExecutor` ships with `spring-boot-starter-data-jpa`, already present.
