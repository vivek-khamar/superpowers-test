# DEMO-3: User Onboarding Endpoint — Design

- **Jira**: https://golinkup.atlassian.net/browse/DEMO-3
- **Branch**: `feat/DEMO-3-implement-user-onboarding-flow`
- **Mode**: Autonomous pipeline (per `CLAUDE.md`) — brainstorm self-approved against the acceptance criteria below; no human sign-off pause required (session is non-interactive and the four scenarios in `.claude/sprint-context.md` are unambiguous). Open engineering choices left by the ticket are resolved explicitly in "Decisions" below.

## Context

Repo is past DEMO-1 (signup) and DEMO-2 (login), both merged to `main`. Real base package is `com.superpowers.test` (sprint-context's `com.example.api` is stale boilerplate text — following the actual code). Existing pieces relevant here:

- `User` entity (`com.superpowers.test.user`): `id`, `email`, `passwordHash`, `name`, `role`, `failedAttempts`, `lockedUntil`, `createdAt`, `updatedAt`. No onboarding fields yet.
- `JwtService` issues HS256 tokens at login with `sub` = user id (as string) and a `roles` claim; `AuthController` sets the token as an HttpOnly/Secure/SameSite=Strict cookie named `jwt`.
- **Gap**: nothing currently *validates* that JWT on incoming requests. `SecurityConfig` only declares `.anyRequest().authenticated()` with signup/login permitted — there is no filter that reads the token and populates `SecurityContext`, so every non-permitted endpoint is currently unreachable regardless of token validity. This ticket is the first to need real request authentication, so building the validation filter is in scope here (AC17–20 require it), not a pre-existing dependency to defer.
- No AWS SDK dependency and no file-storage abstraction exist yet.
- `GlobalExceptionHandler` (`@RestControllerAdvice`) already maps validation/domain exceptions to RFC 7807 `ProblemDetail` — this ticket extends that same handler rather than inventing a new error-mapping mechanism.

## Decisions (resolving ticket's open choices)

1. **JWT transport — accept both `Authorization: Bearer` header and the `jwt` cookie.** AC18 names both ("authorization header or session cookie"), and the login flow only sets a cookie today, so a browser client naturally uses the cookie while a non-browser client (Postman, mobile, tests) can use the header. The filter checks the header first, falls back to the cookie.
2. **Authenticated identity — JWT `sub` claim (user id) becomes the `Authentication` principal name.** `JwtAuthenticationFilter` sets a `UsernamePasswordAuthenticationToken(userId, null, authorities)`; controllers read `Authentication.getName()`. No new principal type needed — matches how `JwtService` already encodes the subject.
3. **401 response shape — a dedicated `AuthenticationEntryPoint`, not `GlobalExceptionHandler`.** Exceptions thrown inside the security filter chain (before `DispatcherServlet` handler mapping) never reach `@RestControllerAdvice`. `RestAuthenticationEntryPoint` writes the RFC 7807 body directly (same technique `SignupRateLimitFilter` already uses for its 429), keeping the `application/problem+json` contract consistent project-wide.
4. **`jobPreference` validation — `@Pattern` on a `String` DTO field, not enum-typed JSON binding.** Binding directly to a Java enum via Jackson throws `HttpMessageNotReadableException` on a bad value, which is a different (and uglier) error path than the `MethodArgumentNotValidException` → 400 + `violations` pattern DEMO-1/DEMO-2 already established. `@Pattern(regexp = "FULL_TIME|PART_TIME")` reuses that exact existing path; the service converts the validated string via `JobPreference.valueOf(...)` immediately after, so persistence is still enum-typed (AC3's "enforced via an Enum" is satisfied at the domain/DB layer).
5. **List fields (`preferredJobFunctions`, `preferredLocations`) — `@ElementCollection` child tables, `@NotNull` only (not `@NotEmpty`).** No AC calls for a minimum-count rule, and inventing one risks contradicting a test that sends an empty list; `@NotNull` just guarantees the part was actually parsed.
6. **File validation — filename-extension based, checked before any S3 call.** Both `profilePicture` and `resume` are validated (extension allow-list; resume additionally checked against a 5MB size cap) at the very start of the service method, throwing a new `InvalidFileException` (400) — satisfies AC10–11's "abort immediately, bypass S3 interaction" literally. Content-Type sniffing is skipped: it's client-supplied and no more trustworthy than the filename at this scope, so it would add complexity without a real security gain.
7. **S3 persistence — store the object key, not a public URL.** The ticket allows either ("object keys/URLs"); the bucket is assumed private (typical for resumes/PII), so persisting a durable key that the app can later resolve via a presigned URL is safer than assuming public access. Column names: `profile_picture_url`, `resume_url` (kept as the ticket's own field naming even though the stored value is a key, to avoid inventing new column-naming vocabulary).
8. **Transactional ordering — upload to S3 before writing to the database, all inside one `@Transactional` service method.** This makes the rollback requirement (AC12–16) fall out of ordering rather than needing manual compensation logic: if either S3 upload throws, zero DB writes have happened yet, so there's nothing to roll back; if the final `save()` somehow throws, Spring's default rollback-on-`RuntimeException` reverts it since it's still inside the same transaction boundary. A best-effort delete of an already-uploaded-but-now-orphaned S3 object (e.g. profile picture succeeds, resume upload then fails) is explicitly **not** implemented — no AC requires it, and the orphaned object is harmless (unreferenced, never surfaced to any user).
9. **New account-status enum, not a boolean.** `UserStatus { REGISTERED, ONBOARDING_COMPLETED }` — matches the ticket's own vocabulary ("update the user's status flag to ONBOARDING_COMPLETED") and leaves room for the existing `REGISTERED` default state from signup, without implying there are only two states forever (YAGNI-safe: exactly the states named in ACs today).

## Architecture

```
JwtAuthenticationFilter (Filter, before dispatch)  -- reads Bearer header / jwt cookie, populates SecurityContext
RestAuthenticationEntryPoint                       -- 401 problem+json on missing/invalid token
SecurityConfig                                     -- registers filter, permits signup/login, requires auth elsewhere

OnboardingController (Controller)
  → OnboardingService (Service, @Transactional)    -- validate files → upload to S3 → persist
      → UserRepository (Spring Data JPA)
      → FileStorageService → S3FileStorageService  -- AWS SDK v2 S3Client
  ← GlobalExceptionHandler (@RestControllerAdvice)  -- RFC7807 mapping (400 validation / file, 500 storage failure)
```

- **`User`** entity gains: `jobPreference` (`JobPreference` enum, `@Enumerated(STRING)`, nullable until onboarded), `preferredJobFunctions`/`preferredLocations` (`@ElementCollection List<String>` with dedicated child tables), `profilePictureUrl`, `resumeUrl` (nullable `VARCHAR`), `status` (`UserStatus` enum, `@Enumerated(STRING)`, `NOT NULL DEFAULT 'REGISTERED'`).
- **Flyway** `V3__add_onboarding_fields_to_users.sql` — new migration only; existing `V1`/`V2` untouched (CLAUDE.md §7). Adds columns to `users` plus two child tables (`user_job_functions`, `user_preferred_locations`), each `(user_id BIGINT REFERENCES users(id), value VARCHAR(255))`.
- **`JobPreference`** enum: `FULL_TIME`, `PART_TIME`. **`UserStatus`** enum: `REGISTERED`, `ONBOARDING_COMPLETED`. Both in `com.superpowers.test.user`.
- **`JwtAuthenticationFilter`** (`OncePerRequestFilter`, `com.superpowers.test.auth`): extracts token (header first, then cookie) → `jwtService.parseClaims(token)` → on success sets `Authentication` with principal = `sub` claim, authority = `"ROLE_" + roles claim`; on missing token, passes through unauthenticated (lets `anyRequest().authenticated()` reject it); on malformed/expired token, clears context and lets the request continue unauthenticated too (same 401 outcome via the entry point, avoiding a second error-handling path).
- **`RestAuthenticationEntryPoint`** (`com.superpowers.test.auth`): implements `AuthenticationEntryPoint`, writes `{"errorCode":"UNAUTHENTICATED","message":"Authentication is required to access this resource."}` as `application/problem+json`, HTTP 401.
- **`SecurityConfig`** updated: registers `JwtAuthenticationFilter` before `UsernamePasswordAuthenticationFilter`; adds `.exceptionHandling(e -> e.authenticationEntryPoint(restAuthenticationEntryPoint))`.
- **`OnboardingRequest`** DTO (`com.superpowers.test.onboarding.dto`, bound from the `profileData` JSON part via `@RequestPart @Valid`): `name` (`@NotBlank`), `jobPreference` (`@NotBlank @Pattern(regexp = "FULL_TIME|PART_TIME")`), `preferredJobFunctions`/`preferredLocations` (`@NotNull List<String>`).
- **`OnboardingController`**: `@PutMapping("/api/v1/user/onboarding")`, consumes `multipart/form-data`, params: `@RequestPart("profileData") @Valid OnboardingRequest`, `@RequestPart("profilePicture") MultipartFile`, `@RequestPart("resume") MultipartFile`, plus `Authentication` for the current user id. Delegates to `OnboardingService.completeOnboarding(...)`, returns 200 with the ticket's success payload.
- **`OnboardingService.completeOnboarding(userId, request, profilePicture, resume)`** (`@Transactional`):
  1. Load `User` by id (not found → internal error, logged — this can only happen if a user is deleted after token issuance; no AC covers it, treated as a 500 edge case, not a new business rule).
  2. Validate `resume` extension (`.pdf`/`.docx`) and size (≤ 5MB); validate `profilePicture` extension (`.jpg`/`.jpeg`/`.png`). Any violation → `InvalidFileException` (400) before touching S3.
  3. Upload `profilePicture` to `profile-pictures/{userId}/{uuid}.{ext}`, then `resume` to `resumes/{userId}/{uuid}.{ext}`, via `FileStorageService`. Any `FileStorageException` propagates uncaught → transaction rolls back, nothing persisted.
  4. Update `User` fields (`name`, `jobPreference` via `valueOf`, lists, both keys, `status = ONBOARDING_COMPLETED`), save.
  5. Return success payload.
- **`FileStorageService`** (`com.superpowers.test.storage`): interface `String upload(String key, MultipartFile file)`. **`S3FileStorageService`** implementation using `software.amazon.awssdk:s3`; bucket/region via `AwsS3Properties` (`@ConfigurationProperties(prefix = "app.aws.s3")`, fields `bucketName`, `region`, optional `endpointOverride` for LocalStack in tests). Wraps any SDK exception in `FileStorageException` (unchecked).
- **`GlobalExceptionHandler`** additions: `InvalidFileException` → 400 (`errorCode=INVALID_FILE`), `FileStorageException` → 500 (`errorCode=ONBOARDING_FAILED`, generic detail — the real exception is logged server-side via SLF4J, never returned to the client).

## Out of scope (explicitly)

- Presigned-URL generation for later file retrieval/download — nothing in the ACs asks for reading the files back, only storing the key.
- Best-effort cleanup of an orphaned S3 object when a later step in the same request fails — see Decision 8.
- Re-onboarding / editing an already-`ONBOARDING_COMPLETED` profile — the ticket describes a one-time flow for "newly registered users"; no AC covers updates after completion, so the endpoint is not idempotency-guarded beyond normal overwrite-on-resubmit behavior.
- Antivirus/content scanning of uploaded files — not in any AC.
- Refresh tokens / token revocation — out of this ticket; `JwtAuthenticationFilter` only validates signature + expiry, same trust model DEMO-2 already established for issuance.

## Testing

- **Unit** (JUnit5 + Mockito + AssertJ): `OnboardingServiceTest` — happy path (upload order, field mapping, status flip); file-validation rejections (bad extension, oversized resume) verify S3 is never called; S3 failure on either file propagates and no `save()` occurs. `JwtAuthenticationFilterTest` — valid header, valid cookie, missing token, expired/malformed token. `OnboardingRequestValidationTest` — blank name, invalid `jobPreference` values, null lists.
- **Integration** (`@SpringBootTest` + Testcontainers Postgres + Testcontainers LocalStack for S3): `OnboardingControllerIntegrationTest` — 200 with real multipart request (valid JWT, valid files) and DB/S3 state assertions; 400 for bad `jobPreference`; 400 for oversized/wrong-format resume with S3 untouched; 401 with no `Authorization`/cookie; 500 simulated via LocalStack fault injection or a Mockito-overridden `FileStorageService` bean, asserting the `users` row is absent/unchanged afterward (transactional rollback proof).
- **Coverage**: JaCoCo ≥80% line coverage on new code, per CLAUDE.md §5.

## New dependencies (`pom.xml`)

- `software.amazon.awssdk:s3` (main) — S3 client.
- `org.testcontainers:localstack` (test) — local S3 for integration tests.
