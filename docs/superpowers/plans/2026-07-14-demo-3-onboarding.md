# DEMO-3: User Onboarding Endpoint — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `PUT /api/v1/user/onboarding` — a JWT-authenticated, multipart endpoint that persists onboarding metadata (name, job preference, job functions, locations) to Postgres, streams a profile picture and resume to S3, and flips the user's status to `ONBOARDING_COMPLETED`, all inside one transactional boundary.

**Architecture:** `OnboardingController` → `OnboardingService` (`@Transactional`) → `UserRepository` (JPA) + `FileStorageService` → `S3FileStorageService` (AWS SDK v2). A new `JwtAuthenticationFilter` (there is currently no code that validates JWTs on incoming requests — only issuance exists) populates `SecurityContext` from the `Authorization` header or `jwt` cookie so `.anyRequest().authenticated()` actually has something to check against.

**Tech Stack:** Spring Boot 4.1 (Web, Security, Data JPA, Validation), Flyway, PostgreSQL, `io.jsonwebtoken` (jjwt) — already present. New: `software.amazon.awssdk:s3` (main), `org.testcontainers:localstack` (test).

## Global Constraints

- Branch: `feat/DEMO-3-implement-user-onboarding-flow` (already checked out in the worktree).
- Package root: `com.superpowers.test` (NOT `com.example.api` — that's stale sprint-context boilerplate text).
- Controller → Service → Repository, no exceptions. Business logic only in `*.service`-equivalent classes (this codebase's convention: services live directly in the domain package, e.g. `com.superpowers.test.onboarding.OnboardingService`).
- `@Valid` + Bean Validation on every request DTO.
- RFC 7807 `application/problem+json` for every error response, via the existing `GlobalExceptionHandler` (`com.superpowers.test.auth.exception.GlobalExceptionHandler`) — extend it, don't create a second handler.
- SLF4J `log.*` only — never `System.out.println`. Never log passwords, tokens, or PII (file contents, raw file bytes).
- `@ConfigurationProperties` for all config; `@ConfigurationPropertiesScan` is already active on `TestApplication`, so a new properties class just needs `@Component @ConfigurationProperties(prefix = "...")` — no manual `@EnableConfigurationProperties` needed.
- Only modify files under `src/main/java/com/example/api/`-equivalent (i.e. `src/main/java/com/superpowers/test/**`) and `src/test/java/com/superpowers/test/**`, plus `pom.xml`, `src/main/resources/application.properties`, and new Flyway migrations. Do **not** touch `V1__create_users_table.sql` or `V2__add_users_created_updated_at.sql`.
- Unit tests: JUnit 5 + Mockito + AssertJ (not `assertEquals`). Integration tests: `@SpringBootTest` + Testcontainers, following the existing `AuthControllerIntegrationTest` / `UserRepositoryTest` patterns exactly (same annotations, same `TestRestTemplate` style).
- Checkstyle: 140-char line limit, no unused/star imports, newline at EOF (`checkstyle.xml`). SpotBugs: effort Max, threshold Medium (`spotbugs-exclude.xml` only excludes `EI_EXPOSE_REP` for `*.dto.*` classes and two named classes — new entity getters that return mutable collections/config objects must defensively copy, not rely on a new exclude entry).
- JaCoCo: ≥80% line coverage on new code.

---

### Task 1: Add AWS S3 SDK + LocalStack test dependency, and onboarding config properties

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.properties`

**Interfaces:**
- Produces: config keys `app.aws.s3.bucket-name`, `app.aws.s3.region`, `app.aws.s3.endpoint-override` (consumed by Task 4's `AwsS3Properties`); `spring.servlet.multipart.max-file-size` / `max-request-size` (infra-level upload cap, not a business rule).

- [ ] **Step 1: Add the AWS SDK S3 dependency and the LocalStack test dependency to `pom.xml`**

Add these two `<dependency>` blocks — the S3 one right after the existing `jjwt-jackson` dependency (still inside the main, non-test dependency group), the LocalStack one inside the test-scoped group, right after `testcontainers-postgresql`:

```xml
		<dependency>
			<groupId>software.amazon.awssdk</groupId>
			<artifactId>s3</artifactId>
			<version>2.47.6</version>
		</dependency>
```

```xml
		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>localstack</artifactId>
			<scope>test</scope>
		</dependency>
```

- [ ] **Step 2: Verify the project still resolves and compiles**

Run: `./mvnw -q compile`
Expected: `BUILD SUCCESS` (no output on `-q` success), exit code 0.

- [ ] **Step 3: Add onboarding-related configuration to `application.properties`**

Append to the end of `src/main/resources/application.properties`:

```properties
# Onboarding file uploads — infra-level safety net, not a business rule.
# The 5MB resume cap and file-type checks are enforced in OnboardingService.
spring.servlet.multipart.max-file-size=10MB
spring.servlet.multipart.max-request-size=25MB

# Must be overridden via env vars in any non-local environment.
app.aws.s3.bucket-name=superpowers-test-onboarding
app.aws.s3.region=us-east-1
app.aws.s3.endpoint-override=
```

- [ ] **Step 4: Commit**

```bash
git add pom.xml src/main/resources/application.properties
git commit -m "$(cat <<'EOF'
feat(DEMO-3): add S3 SDK, LocalStack test dep, and onboarding config

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Extend `User` with onboarding fields, add enums, add Flyway migration

**Files:**
- Create: `src/main/java/com/superpowers/test/user/JobPreference.java`
- Create: `src/main/java/com/superpowers/test/user/UserStatus.java`
- Modify: `src/main/java/com/superpowers/test/user/User.java`
- Create: `src/main/resources/db/migration/V3__add_onboarding_fields_to_users.sql`
- Modify: `src/test/java/com/superpowers/test/user/UserRepositoryTest.java`

**Interfaces:**
- Produces: `JobPreference` enum with constants `FULL_TIME`, `PART_TIME`. `UserStatus` enum with constants `REGISTERED`, `ONBOARDING_COMPLETED`. On `User`: `getJobPreference()/setJobPreference(JobPreference)`, `getPreferredJobFunctions()/setPreferredJobFunctions(List<String>)`, `getPreferredLocations()/setPreferredLocations(List<String>)`, `getProfilePictureUrl()/setProfilePictureUrl(String)`, `getResumeUrl()/setResumeUrl(String)`, `getStatus()/setStatus(UserStatus)`. New `User` rows default `status` to `UserStatus.REGISTERED`.

- [ ] **Step 1: Write the failing repository test for the new fields**

Add these test methods to `src/test/java/com/superpowers/test/user/UserRepositoryTest.java`, right after `defaultsFailedAttemptsToZeroAndLockedUntilToNull`:

```java
    @Test
    void defaultsStatusToRegisteredAndOnboardingFieldsToNull() {
        User saved = userRepository.save(new User("onboarding-default@example.com", "hash", "Fresh User"));

        assertThat(saved.getStatus()).isEqualTo(UserStatus.REGISTERED);
        assertThat(saved.getJobPreference()).isNull();
        assertThat(saved.getPreferredJobFunctions()).isEmpty();
        assertThat(saved.getPreferredLocations()).isEmpty();
        assertThat(saved.getProfilePictureUrl()).isNull();
        assertThat(saved.getResumeUrl()).isNull();
    }

    @Test
    void persistsOnboardingFieldsIncludingListCollections() {
        User user = new User("onboarding-complete@example.com", "hash", "Onboarded User");
        user.setJobPreference(JobPreference.FULL_TIME);
        user.setPreferredJobFunctions(List.of("Backend Engineer", "Solution Architect"));
        user.setPreferredLocations(List.of("Mumbai", "Bangalore"));
        user.setProfilePictureUrl("profile-pictures/1/abc.png");
        user.setResumeUrl("resumes/1/abc.pdf");
        user.setStatus(UserStatus.ONBOARDING_COMPLETED);

        User saved = userRepository.saveAndFlush(user);
        userRepository.flush();
        User reloaded = userRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getJobPreference()).isEqualTo(JobPreference.FULL_TIME);
        assertThat(reloaded.getPreferredJobFunctions()).containsExactlyInAnyOrder("Backend Engineer", "Solution Architect");
        assertThat(reloaded.getPreferredLocations()).containsExactlyInAnyOrder("Mumbai", "Bangalore");
        assertThat(reloaded.getProfilePictureUrl()).isEqualTo("profile-pictures/1/abc.png");
        assertThat(reloaded.getResumeUrl()).isEqualTo("resumes/1/abc.pdf");
        assertThat(reloaded.getStatus()).isEqualTo(UserStatus.ONBOARDING_COMPLETED);
    }
```

Add these two imports to the top of the same file, alongside the existing imports:

```java
import java.util.List;
```

(No import needed for `JobPreference`/`UserStatus` — same package `com.superpowers.test.user` as the test class.)

- [ ] **Step 2: Run the test to verify it fails to compile (fields/enums don't exist yet)**

Run: `./mvnw -q test -Dtest=UserRepositoryTest`
Expected: Compilation error — `cannot find symbol: class JobPreference` (or similar), confirming the test exercises code that doesn't exist yet.

- [ ] **Step 3: Create the `JobPreference` enum**

```java
package com.superpowers.test.user;

public enum JobPreference {
    FULL_TIME,
    PART_TIME
}
```

- [ ] **Step 4: Create the `UserStatus` enum**

```java
package com.superpowers.test.user;

public enum UserStatus {
    REGISTERED,
    ONBOARDING_COMPLETED
}
```

- [ ] **Step 5: Add the new fields, getters, and setters to `User.java`**

Add these imports to `src/main/java/com/superpowers/test/user/User.java`, alongside the existing `jakarta.persistence.*` imports:

```java
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
```

Add these fields right after the `lockedUntil` field (before the `@CreationTimestamp createdAt` field):

```java
    @Enumerated(EnumType.STRING)
    @Column(name = "job_preference")
    private JobPreference jobPreference;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_job_functions", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "value")
    private List<String> preferredJobFunctions = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_preferred_locations", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "value")
    private List<String> preferredLocations = new ArrayList<>();

    @Column(name = "profile_picture_url")
    private String profilePictureUrl;

    @Column(name = "resume_url")
    private String resumeUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status = UserStatus.REGISTERED;
```

Add these getters/setters right after the existing `getUpdatedAt()` method, before the closing brace of the class:

```java

    public JobPreference getJobPreference() {
        return jobPreference;
    }

    public void setJobPreference(JobPreference jobPreference) {
        this.jobPreference = jobPreference;
    }

    public List<String> getPreferredJobFunctions() {
        return Collections.unmodifiableList(preferredJobFunctions);
    }

    public void setPreferredJobFunctions(List<String> preferredJobFunctions) {
        this.preferredJobFunctions = new ArrayList<>(preferredJobFunctions);
    }

    public List<String> getPreferredLocations() {
        return Collections.unmodifiableList(preferredLocations);
    }

    public void setPreferredLocations(List<String> preferredLocations) {
        this.preferredLocations = new ArrayList<>(preferredLocations);
    }

    public String getProfilePictureUrl() {
        return profilePictureUrl;
    }

    public void setProfilePictureUrl(String profilePictureUrl) {
        this.profilePictureUrl = profilePictureUrl;
    }

    public String getResumeUrl() {
        return resumeUrl;
    }

    public void setResumeUrl(String resumeUrl) {
        this.resumeUrl = resumeUrl;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }
```

- [ ] **Step 6: Create the Flyway migration**

```sql
ALTER TABLE users
    ADD COLUMN job_preference VARCHAR(20),
    ADD COLUMN profile_picture_url VARCHAR(500),
    ADD COLUMN resume_url VARCHAR(500),
    ADD COLUMN status VARCHAR(30) NOT NULL DEFAULT 'REGISTERED';

CREATE TABLE user_job_functions (
    user_id BIGINT NOT NULL REFERENCES users(id),
    value VARCHAR(255) NOT NULL
);

CREATE TABLE user_preferred_locations (
    user_id BIGINT NOT NULL REFERENCES users(id),
    value VARCHAR(255) NOT NULL
);

CREATE INDEX ix_user_job_functions_user_id ON user_job_functions (user_id);
CREATE INDEX ix_user_preferred_locations_user_id ON user_preferred_locations (user_id);
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `./mvnw -q test -Dtest=UserRepositoryTest`
Expected: `Tests run: 6, Failures: 0, Errors: 0` (4 existing + 2 new), Flyway log shows migration `3 - add onboarding fields to users` applied.

- [ ] **Step 8: Run full unit+integration test suite to check nothing else broke**

Run: `./mvnw -q test`
Expected: `BUILD SUCCESS`.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/superpowers/test/user/ src/main/resources/db/migration/V3__add_onboarding_fields_to_users.sql src/test/java/com/superpowers/test/user/UserRepositoryTest.java
git commit -m "$(cat <<'EOF'
feat(DEMO-3): add onboarding fields to User entity

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: JWT request authentication (filter + 401 entry point + SecurityConfig wiring)

**Context:** No code currently validates a JWT on an incoming request — `JwtService` only *issues* tokens at login. `SecurityConfig` declares `.anyRequest().authenticated()` but nothing populates `SecurityContext`, so this task is a real prerequisite for AC17–20, not pre-existing infrastructure.

**Files:**
- Create: `src/main/java/com/superpowers/test/auth/JwtAuthenticationFilter.java`
- Create: `src/main/java/com/superpowers/test/auth/RestAuthenticationEntryPoint.java`
- Modify: `src/main/java/com/superpowers/test/config/SecurityConfig.java`
- Create: `src/test/java/com/superpowers/test/auth/JwtAuthenticationFilterTest.java`
- Create: `src/test/java/com/superpowers/test/auth/RestAuthenticationEntryPointTest.java`

**Interfaces:**
- Consumes: `JwtService.parseClaims(String token)` returning `io.jsonwebtoken.Claims` (throws `io.jsonwebtoken.JwtException` subtypes on invalid/expired tokens) — from `com.superpowers.test.auth.JwtService`, already exists.
- Produces: after a request passes through `JwtAuthenticationFilter` with a valid token, `SecurityContextHolder.getContext().getAuthentication().getName()` returns the JWT's `sub` claim (the user id, as a string). `OnboardingController` (Task 8) relies on this.

- [ ] **Step 1: Write the failing filter unit test**

```java
package com.superpowers.test.auth;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthenticationFilterTest {

    private JwtService jwtService;
    private JwtAuthenticationFilter filter;
    private FilterChain passThroughChain;

    @BeforeEach
    void setUp() {
        AppJwtProperties properties = new AppJwtProperties();
        properties.setSecret("test-secret-key-that-is-at-least-32-bytes-long!!");
        properties.setExpirationSeconds(86400);
        jwtService = new JwtService(properties);
        filter = new JwtAuthenticationFilter(jwtService);
        passThroughChain = (req, res) -> ((MockHttpServletResponse) res).setStatus(200);
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatesFromBearerHeader() throws Exception {
        String token = jwtService.generateToken("42", "USER");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);

        filter.doFilter(request, new MockHttpServletResponse(), passThroughChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("42");
    }

    @Test
    void authenticatesFromJwtCookieWhenNoHeaderPresent() throws Exception {
        String token = jwtService.generateToken("99", "USER");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("jwt", token));

        filter.doFilter(request, new MockHttpServletResponse(), passThroughChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("99");
    }

    @Test
    void leavesContextEmptyWhenNoTokenPresent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();

        filter.doFilter(request, new MockHttpServletResponse(), passThroughChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void leavesContextEmptyForMalformedToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer not-a-real-token");

        filter.doFilter(request, new MockHttpServletResponse(), passThroughChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `./mvnw -q test -Dtest=JwtAuthenticationFilterTest`
Expected: Compilation error — `cannot find symbol: class JwtAuthenticationFilter`.

- [ ] **Step 3: Create `JwtAuthenticationFilter`**

```java
package com.superpowers.test.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String COOKIE_NAME = "jwt";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        resolveToken(request).ifPresent(this::authenticate);
        filterChain.doFilter(request, response);
    }

    private void authenticate(String token) {
        try {
            Claims claims = jwtService.parseClaims(token);
            String userId = claims.getSubject();
            String role = claims.get("roles", String.class);
            List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
            var authentication = new UsernamePasswordAuthenticationToken(userId, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtException ex) {
            log.warn("Rejected request with invalid JWT: {}", ex.getMessage());
            SecurityContextHolder.clearContext();
        }
    }

    private Optional<String> resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return Optional.of(header.substring(BEARER_PREFIX.length()));
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (COOKIE_NAME.equals(cookie.getName())) {
                    return Optional.of(cookie.getValue());
                }
            }
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q test -Dtest=JwtAuthenticationFilterTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0`.

- [ ] **Step 5: Write the failing entry point unit test**

```java
package com.superpowers.test.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

class RestAuthenticationEntryPointTest {

    @Test
    void writes401ProblemJsonBody() throws Exception {
        RestAuthenticationEntryPoint entryPoint = new RestAuthenticationEntryPoint();
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(new MockHttpServletRequest(), response, new BadCredentialsException("no auth"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getContentAsString()).contains("\"errorCode\":\"UNAUTHENTICATED\"");
    }
}
```

- [ ] **Step 6: Run the test to verify it fails to compile**

Run: `./mvnw -q test -Dtest=RestAuthenticationEntryPointTest`
Expected: Compilation error — `cannot find symbol: class RestAuthenticationEntryPoint`.

- [ ] **Step 7: Create `RestAuthenticationEntryPoint`**

```java
package com.superpowers.test.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(
                "{\"errorCode\":\"UNAUTHENTICATED\",\"message\":\"Authentication is required to access this resource.\"}");
    }
}
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `./mvnw -q test -Dtest=RestAuthenticationEntryPointTest`
Expected: `Tests run: 1, Failures: 0, Errors: 0`.

- [ ] **Step 9: Wire the filter and entry point into `SecurityConfig`**

Replace the full contents of `src/main/java/com/superpowers/test/config/SecurityConfig.java` with:

```java
package com.superpowers.test.config;

import com.superpowers.test.auth.JwtAuthenticationFilter;
import com.superpowers.test.auth.JwtService;
import com.superpowers.test.auth.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, JwtService jwtService, RestAuthenticationEntryPoint restAuthenticationEntryPoint) {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling.authenticationEntryPoint(restAuthenticationEntryPoint))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/signup", "/api/v1/auth/login").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

- [ ] **Step 10: Run the full test suite to confirm existing login/signup flows still work**

Run: `./mvnw -q test`
Expected: `BUILD SUCCESS` — `AuthControllerIntegrationTest` and `SignupControllerIntegrationTest` still pass unmodified (their paths are still `permitAll()`).

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/superpowers/test/auth/JwtAuthenticationFilter.java src/main/java/com/superpowers/test/auth/RestAuthenticationEntryPoint.java src/main/java/com/superpowers/test/config/SecurityConfig.java src/test/java/com/superpowers/test/auth/JwtAuthenticationFilterTest.java src/test/java/com/superpowers/test/auth/RestAuthenticationEntryPointTest.java
git commit -m "$(cat <<'EOF'
feat(DEMO-3): validate JWTs on incoming requests

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: S3 file storage abstraction

**Files:**
- Create: `src/main/java/com/superpowers/test/storage/FileStorageException.java`
- Create: `src/main/java/com/superpowers/test/storage/FileStorageService.java`
- Create: `src/main/java/com/superpowers/test/storage/AwsS3Properties.java`
- Create: `src/main/java/com/superpowers/test/storage/S3ClientConfig.java`
- Create: `src/main/java/com/superpowers/test/storage/S3FileStorageService.java`
- Create: `src/test/java/com/superpowers/test/storage/S3FileStorageServiceIntegrationTest.java`

**Interfaces:**
- Produces: `FileStorageService.upload(String key, MultipartFile file)` → returns the same `key` on success, throws unchecked `FileStorageException` on any storage failure. Consumed by `OnboardingService` (Task 6).
- Produces: `AwsS3Properties` with `getBucketName()`, `getRegion()`, `getEndpointOverride()` (nullable/blank when unset).

- [ ] **Step 1: Create `FileStorageException`**

```java
package com.superpowers.test.storage;

public class FileStorageException extends RuntimeException {

    public FileStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 2: Create the `FileStorageService` interface**

```java
package com.superpowers.test.storage;

import org.springframework.web.multipart.MultipartFile;

public interface FileStorageService {

    String upload(String key, MultipartFile file);
}
```

- [ ] **Step 3: Create `AwsS3Properties`**

```java
package com.superpowers.test.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.aws.s3")
public class AwsS3Properties {

    private String bucketName = "";
    private String region = "us-east-1";
    private String endpointOverride = "";

    public String getBucketName() {
        return bucketName;
    }

    public void setBucketName(String bucketName) {
        this.bucketName = bucketName;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getEndpointOverride() {
        return endpointOverride;
    }

    public void setEndpointOverride(String endpointOverride) {
        this.endpointOverride = endpointOverride;
    }
}
```

- [ ] **Step 4: Create `S3ClientConfig`**

When `app.aws.s3.endpoint-override` is set (LocalStack in tests), use static dummy credentials and path-style access, since LocalStack doesn't validate real AWS credentials. Otherwise use the SDK's default credential provider chain for real AWS.

```java
package com.superpowers.test.storage;

import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

@Configuration
public class S3ClientConfig {

    @Bean
    public S3Client s3Client(AwsS3Properties properties) {
        S3ClientBuilder builder = S3Client.builder().region(Region.of(properties.getRegion()));

        if (properties.getEndpointOverride() != null && !properties.getEndpointOverride().isBlank()) {
            builder.endpointOverride(URI.create(properties.getEndpointOverride()))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                    .forcePathStyle(true);
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }

        return builder.build();
    }
}
```

- [ ] **Step 5: Write the failing LocalStack integration test**

```java
package com.superpowers.test.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
class S3FileStorageServiceIntegrationTest {

    private static final String BUCKET_NAME = "onboarding-test-bucket";

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
            .withServices(LocalStackContainer.Service.S3);

    @Autowired
    private FileStorageService fileStorageService;

    @BeforeAll
    static void createBucket() throws Exception {
        localstack.execInContainer("awslocal", "s3", "mb", "s3://" + BUCKET_NAME);
    }

    @DynamicPropertySource
    static void awsProperties(DynamicPropertyRegistry registry) {
        registry.add("app.aws.s3.bucket-name", () -> BUCKET_NAME);
        registry.add("app.aws.s3.region", () -> localstack.getRegion());
        registry.add(
                "app.aws.s3.endpoint-override",
                () -> localstack.getEndpointOverride(LocalStackContainer.Service.S3).toString());
    }

    @Test
    void uploadsFileAndReturnsTheGivenKey() {
        MockMultipartFile file = new MockMultipartFile("resume", "resume.pdf", "application/pdf", "content".getBytes());

        String returnedKey = fileStorageService.upload("resumes/1/resume.pdf", file);

        assertThat(returnedKey).isEqualTo("resumes/1/resume.pdf");
    }

    @Test
    void wrapsAnUploadFailureIntoFileStorageException() {
        MockMultipartFile brokenFile =
                new MockMultipartFile("resume", "resume.pdf", "application/pdf", "content".getBytes()) {
                    @Override
                    public java.io.InputStream getInputStream() throws java.io.IOException {
                        throw new java.io.IOException("simulated read failure");
                    }
                };

        assertThatThrownBy(() -> fileStorageService.upload("resumes/1/broken.pdf", brokenFile))
                .isInstanceOf(FileStorageException.class);
    }
}
```

- [ ] **Step 6: Run the test to verify it fails to compile**

Run: `./mvnw -q test -Dtest=S3FileStorageServiceIntegrationTest`
Expected: Compilation error — `cannot find symbol: class S3FileStorageService` (referenced transitively; no `FileStorageService` bean implementation exists yet, so context load fails).

- [ ] **Step 7: Create `S3FileStorageService`**

```java
package com.superpowers.test.storage;

import java.io.IOException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
public class S3FileStorageService implements FileStorageService {

    private final S3Client s3Client;
    private final AwsS3Properties properties;

    public S3FileStorageService(S3Client s3Client, AwsS3Properties properties) {
        this.s3Client = s3Client;
        this.properties = properties;
    }

    @Override
    public String upload(String key, MultipartFile file) {
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(properties.getBucketName())
                    .key(key)
                    .contentType(file.getContentType())
                    .build();
            s3Client.putObject(request, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
            return key;
        } catch (IOException | SdkException ex) {
            throw new FileStorageException("Failed to upload file to storage: " + key, ex);
        }
    }
}
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `./mvnw -q test -Dtest=S3FileStorageServiceIntegrationTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0` (LocalStack container starts, bucket created, upload succeeds, simulated IO failure wraps into `FileStorageException`).

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/superpowers/test/storage/ src/test/java/com/superpowers/test/storage/
git commit -m "$(cat <<'EOF'
feat(DEMO-3): add S3-backed file storage service

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Onboarding request/response DTOs

**Files:**
- Create: `src/main/java/com/superpowers/test/onboarding/dto/OnboardingRequest.java`
- Create: `src/main/java/com/superpowers/test/onboarding/dto/OnboardingResponse.java`
- Create: `src/test/java/com/superpowers/test/onboarding/dto/OnboardingRequestValidationTest.java`

**Interfaces:**
- Produces: `OnboardingRequest(String name, String jobPreference, List<String> preferredJobFunctions, List<String> preferredLocations)` — a record. `OnboardingResponse(String status, String message)` — a record with static `OnboardingResponse.success()`. Consumed by `OnboardingController`/`OnboardingService` (Tasks 6, 8).

- [ ] **Step 1: Write the failing validation test**

```java
package com.superpowers.test.onboarding.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OnboardingRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    private OnboardingRequest validRequest(String jobPreference) {
        return new OnboardingRequest(
                "Vivek Khamar", jobPreference, List.of("Backend Engineer"), List.of("Mumbai"));
    }

    @Test
    void acceptsAWellFormedRequest() {
        Set<ConstraintViolation<OnboardingRequest>> violations = validator.validate(validRequest("FULL_TIME"));

        assertThat(violations).isEmpty();
    }

    @Test
    void acceptsPartTimeAsWellAsFullTime() {
        assertThat(validator.validate(validRequest("PART_TIME"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"full_time", "CONTRACT", "FULLTIME", ""})
    void rejectsJobPreferenceValuesOtherThanTheTwoAllowed(String badValue) {
        assertThat(validator.validate(validRequest(badValue))).isNotEmpty();
    }

    @Test
    void rejectsBlankName() {
        OnboardingRequest request = new OnboardingRequest("", "FULL_TIME", List.of("Backend Engineer"), List.of("Mumbai"));

        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    void rejectsNullLists() {
        OnboardingRequest request = new OnboardingRequest("Vivek Khamar", "FULL_TIME", null, null);

        assertThat(validator.validate(request)).hasSize(2);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `./mvnw -q test -Dtest=OnboardingRequestValidationTest`
Expected: Compilation error — `cannot find symbol: class OnboardingRequest`.

- [ ] **Step 3: Create `OnboardingRequest`**

```java
package com.superpowers.test.onboarding.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;

public record OnboardingRequest(

        @NotBlank(message = "name is required")
        String name,

        @NotBlank(message = "jobPreference is required")
        @Pattern(regexp = "FULL_TIME|PART_TIME", message = "jobPreference must be one of FULL_TIME or PART_TIME")
        String jobPreference,

        @NotNull(message = "preferredJobFunctions is required")
        List<String> preferredJobFunctions,

        @NotNull(message = "preferredLocations is required")
        List<String> preferredLocations) {
}
```

- [ ] **Step 4: Create `OnboardingResponse`**

```java
package com.superpowers.test.onboarding.dto;

public record OnboardingResponse(String status, String message) {

    public static OnboardingResponse success() {
        return new OnboardingResponse("success", "User onboarding profile completed successfully.");
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -q test -Dtest=OnboardingRequestValidationTest`
Expected: `Tests run: 6, Failures: 0, Errors: 0`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/superpowers/test/onboarding/dto/ src/test/java/com/superpowers/test/onboarding/dto/
git commit -m "$(cat <<'EOF'
feat(DEMO-3): add onboarding request/response DTOs

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: `OnboardingService` — validation, upload ordering, transactional persistence

**Files:**
- Create: `src/main/java/com/superpowers/test/onboarding/exception/InvalidFileException.java`
- Create: `src/main/java/com/superpowers/test/onboarding/OnboardingService.java`
- Create: `src/test/java/com/superpowers/test/onboarding/OnboardingServiceTest.java`

**Interfaces:**
- Consumes: `UserRepository` (`findById(Long)`, `save(User)` — both already exist via `JpaRepository<User, Long>`), `FileStorageService.upload(String, MultipartFile)` (Task 4), `OnboardingRequest`/`OnboardingResponse` (Task 5), `User` setters from Task 2.
- Produces: `OnboardingService.completeOnboarding(Long userId, OnboardingRequest request, MultipartFile profilePicture, MultipartFile resume)` returning `OnboardingResponse`, throwing `InvalidFileException` (bad file type/size, thrown before any S3 call) or propagating `FileStorageException` (S3 failure — no DB write has occurred yet). Consumed by `OnboardingController` (Task 8).

- [ ] **Step 1: Write the failing service unit test**

```java
package com.superpowers.test.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.superpowers.test.onboarding.dto.OnboardingRequest;
import com.superpowers.test.onboarding.dto.OnboardingResponse;
import com.superpowers.test.onboarding.exception.InvalidFileException;
import com.superpowers.test.storage.FileStorageException;
import com.superpowers.test.storage.FileStorageService;
import com.superpowers.test.user.JobPreference;
import com.superpowers.test.user.User;
import com.superpowers.test.user.UserRepository;
import com.superpowers.test.user.UserStatus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

class OnboardingServiceTest {

    private static final Long USER_ID = 7L;

    private UserRepository userRepository;
    private FileStorageService fileStorageService;
    private OnboardingService onboardingService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        fileStorageService = mock(FileStorageService.class);
        onboardingService = new OnboardingService(userRepository, fileStorageService);
    }

    private User existingUser() {
        User user = new User("user@example.com", "hash", "Old Name");
        ReflectionTestUtils.setField(user, "id", USER_ID);
        return user;
    }

    private OnboardingRequest validRequest() {
        return new OnboardingRequest(
                "Vivek Khamar", "FULL_TIME", List.of("Backend Engineer", "Solution Architect"),
                List.of("Mumbai", "Bangalore"));
    }

    private MultipartFile picture() {
        return new MockMultipartFile("profilePicture", "photo.png", "image/png", "img-bytes".getBytes());
    }

    private MultipartFile resume(byte[] content) {
        return new MockMultipartFile("resume", "resume.pdf", "application/pdf", content);
    }

    // AC1-6
    @Test
    void uploadsBothFilesThenPersistsMetadataAndFlipsStatus() {
        User user = existingUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(fileStorageService.upload(any(), eq(picture()))).thenReturn("profile-pictures/7/x.png");
        when(fileStorageService.upload(any(), any())).thenReturn("returned-key");

        OnboardingResponse response = onboardingService.completeOnboarding(USER_ID, validRequest(), picture(), resume("content".getBytes()));

        assertThat(response.status()).isEqualTo("success");
        assertThat(response.message()).isEqualTo("User onboarding profile completed successfully.");
        assertThat(user.getName()).isEqualTo("Vivek Khamar");
        assertThat(user.getJobPreference()).isEqualTo(JobPreference.FULL_TIME);
        assertThat(user.getPreferredJobFunctions()).containsExactly("Backend Engineer", "Solution Architect");
        assertThat(user.getPreferredLocations()).containsExactly("Mumbai", "Bangalore");
        assertThat(user.getProfilePictureUrl()).isEqualTo("returned-key");
        assertThat(user.getResumeUrl()).isEqualTo("returned-key");
        assertThat(user.getStatus()).isEqualTo(UserStatus.ONBOARDING_COMPLETED);
        verify(userRepository).save(user);
    }

    // AC10-11: bad resume format rejected before any S3 interaction
    @Test
    void rejectsWrongResumeFormatWithoutTouchingStorage() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existingUser()));
        MultipartFile badResume = new MockMultipartFile("resume", "resume.txt", "text/plain", "content".getBytes());

        assertThatThrownBy(() -> onboardingService.completeOnboarding(USER_ID, validRequest(), picture(), badResume))
                .isInstanceOf(InvalidFileException.class);

        verify(fileStorageService, never()).upload(any(), any());
        verify(userRepository, never()).save(any());
    }

    // AC10-11: oversized resume rejected before any S3 interaction
    @Test
    void rejectsOversizedResumeWithoutTouchingStorage() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existingUser()));
        byte[] oversized = new byte[5 * 1024 * 1024 + 1];
        MultipartFile bigResume = new MockMultipartFile("resume", "resume.pdf", "application/pdf", oversized);

        assertThatThrownBy(() -> onboardingService.completeOnboarding(USER_ID, validRequest(), picture(), bigResume))
                .isInstanceOf(InvalidFileException.class);

        verify(fileStorageService, never()).upload(any(), any());
        verify(userRepository, never()).save(any());
    }

    // Profile picture format constraint (technical design section of the ticket)
    @Test
    void rejectsWrongProfilePictureFormatWithoutTouchingStorage() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existingUser()));
        MultipartFile badPicture = new MockMultipartFile("profilePicture", "photo.gif", "image/gif", "bytes".getBytes());

        assertThatThrownBy(() -> onboardingService.completeOnboarding(USER_ID, validRequest(), badPicture, resume("content".getBytes())))
                .isInstanceOf(InvalidFileException.class);

        verify(fileStorageService, never()).upload(any(), any());
        verify(userRepository, never()).save(any());
    }

    // AC12-16: S3 failure means no DB write occurs
    @Test
    void propagatesStorageFailureWithoutPersistingAnything() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existingUser()));
        when(fileStorageService.upload(any(), any())).thenThrow(new FileStorageException("boom", new RuntimeException()));

        assertThatThrownBy(() -> onboardingService.completeOnboarding(USER_ID, validRequest(), picture(), resume("content".getBytes())))
                .isInstanceOf(FileStorageException.class);

        verify(userRepository, never()).save(any());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `./mvnw -q test -Dtest=OnboardingServiceTest`
Expected: Compilation error — `cannot find symbol: class OnboardingService` / `class InvalidFileException`.

- [ ] **Step 3: Create `InvalidFileException`**

```java
package com.superpowers.test.onboarding.exception;

public class InvalidFileException extends RuntimeException {

    public InvalidFileException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Create `OnboardingService`**

```java
package com.superpowers.test.onboarding;

import com.superpowers.test.onboarding.dto.OnboardingRequest;
import com.superpowers.test.onboarding.dto.OnboardingResponse;
import com.superpowers.test.onboarding.exception.InvalidFileException;
import com.superpowers.test.storage.FileStorageService;
import com.superpowers.test.user.JobPreference;
import com.superpowers.test.user.User;
import com.superpowers.test.user.UserRepository;
import com.superpowers.test.user.UserStatus;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class OnboardingService {

    private static final Logger log = LoggerFactory.getLogger(OnboardingService.class);
    private static final Set<String> ALLOWED_PICTURE_EXTENSIONS = Set.of("jpg", "jpeg", "png");
    private static final Set<String> ALLOWED_RESUME_EXTENSIONS = Set.of("pdf", "docx");
    private static final long MAX_RESUME_SIZE_BYTES = 5L * 1024 * 1024;

    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;

    public OnboardingService(UserRepository userRepository, FileStorageService fileStorageService) {
        this.userRepository = userRepository;
        this.fileStorageService = fileStorageService;
    }

    @Transactional
    public OnboardingResponse completeOnboarding(
            Long userId, OnboardingRequest request, MultipartFile profilePicture, MultipartFile resume) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user " + userId + " no longer exists"));

        validateProfilePicture(profilePicture);
        validateResume(resume);

        String profilePictureKey = fileStorageService.upload(
                "profile-pictures/" + userId + "/" + UUID.randomUUID() + "." + extensionOf(profilePicture),
                profilePicture);
        String resumeKey = fileStorageService.upload(
                "resumes/" + userId + "/" + UUID.randomUUID() + "." + extensionOf(resume),
                resume);

        user.setName(request.name());
        user.setJobPreference(JobPreference.valueOf(request.jobPreference()));
        user.setPreferredJobFunctions(request.preferredJobFunctions());
        user.setPreferredLocations(request.preferredLocations());
        user.setProfilePictureUrl(profilePictureKey);
        user.setResumeUrl(resumeKey);
        user.setStatus(UserStatus.ONBOARDING_COMPLETED);
        userRepository.save(user);

        log.info("Onboarding completed for user {}", userId);
        return OnboardingResponse.success();
    }

    private void validateProfilePicture(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidFileException("profilePicture is required.");
        }
        if (!ALLOWED_PICTURE_EXTENSIONS.contains(extensionOf(file))) {
            throw new InvalidFileException("profilePicture must be one of: .jpg, .jpeg, .png");
        }
    }

    private void validateResume(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidFileException("resume is required.");
        }
        if (!ALLOWED_RESUME_EXTENSIONS.contains(extensionOf(file))) {
            throw new InvalidFileException("resume must be one of: .pdf, .docx");
        }
        if (file.getSize() > MAX_RESUME_SIZE_BYTES) {
            throw new InvalidFileException("resume must not exceed 5MB.");
        }
    }

    private String extensionOf(MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}
```

Note: `User` needs a `setName(String)` setter, which doesn't exist yet (name is currently constructor-only). Add it to `src/main/java/com/superpowers/test/user/User.java` right after `getName()`:

```java

    public void setName(String name) {
        this.name = name;
    }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -q test -Dtest=OnboardingServiceTest`
Expected: `Tests run: 5, Failures: 0, Errors: 0`.

- [ ] **Step 6: Run the full suite to confirm `setName` addition didn't break anything**

Run: `./mvnw -q test`
Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/superpowers/test/onboarding/ src/main/java/com/superpowers/test/user/User.java src/test/java/com/superpowers/test/onboarding/OnboardingServiceTest.java
git commit -m "$(cat <<'EOF'
feat(DEMO-3): add OnboardingService with file validation and transactional persistence

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Extend `GlobalExceptionHandler` for onboarding errors

**Files:**
- Modify: `src/main/java/com/superpowers/test/auth/exception/GlobalExceptionHandler.java`

**Interfaces:**
- Consumes: `InvalidFileException` (Task 6, package `com.superpowers.test.onboarding.exception`), `FileStorageException` (Task 4, package `com.superpowers.test.storage`).
- Produces: `InvalidFileException` → 400 `application/problem+json`, `errorCode=INVALID_FILE`. `FileStorageException` → 500 `application/problem+json`, `errorCode=ONBOARDING_FAILED`, generic detail message (real exception logged server-side only, never returned to the client).

- [ ] **Step 1: Add imports and a logger to `GlobalExceptionHandler`**

Add these imports right after the existing `java.util.List` import:

```java
import com.superpowers.test.onboarding.exception.InvalidFileException;
import com.superpowers.test.storage.FileStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

Add this field as the first line inside the class body, before `@ExceptionHandler(MethodArgumentNotValidException.class)`:

```java
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

```

- [ ] **Step 2: Add the two new exception handlers**

Add these methods right after `handleAccountLocked`, before the `FieldViolation` record:

```java

    @ExceptionHandler(InvalidFileException.class)
    public ProblemDetail handleInvalidFile(InvalidFileException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid File");
        problem.setProperty("errorCode", "INVALID_FILE");
        problem.setProperty("message", ex.getMessage());
        return problem;
    }

    @ExceptionHandler(FileStorageException.class)
    public ProblemDetail handleFileStorageFailure(FileStorageException ex) {
        log.error("Onboarding file storage failure", ex);
        String detail = "Unable to complete onboarding due to an internal error.";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, detail);
        problem.setTitle("Onboarding Failed");
        problem.setProperty("errorCode", "ONBOARDING_FAILED");
        problem.setProperty("message", detail);
        return problem;
    }
```

- [ ] **Step 3: Compile to confirm no errors**

Run: `./mvnw -q compile`
Expected: `BUILD SUCCESS`. (Behavioral coverage of these two handlers comes from `OnboardingControllerIntegrationTest` in Task 8 — no standalone unit test needed since `GlobalExceptionHandler` has no existing unit tests either, only integration coverage.)

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/superpowers/test/auth/exception/GlobalExceptionHandler.java
git commit -m "$(cat <<'EOF'
feat(DEMO-3): map onboarding exceptions to RFC7807 responses

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: `OnboardingController` + full end-to-end integration tests

**Files:**
- Create: `src/main/java/com/superpowers/test/onboarding/OnboardingController.java`
- Create: `src/test/java/com/superpowers/test/onboarding/OnboardingControllerIntegrationTest.java`
- Create: `src/test/java/com/superpowers/test/onboarding/OnboardingRollbackIntegrationTest.java`

**Interfaces:**
- Consumes: `OnboardingService.completeOnboarding(Long, OnboardingRequest, MultipartFile, MultipartFile)` (Task 6); `Authentication.getName()` (Task 3) for the current user id.
- Produces: `PUT /api/v1/user/onboarding`, `multipart/form-data`, parts `profileData` (JSON), `profilePicture`, `resume`. 200 on success; 400/401/500 via the exception mappings from Tasks 3 and 7.

- [ ] **Step 1: Create `OnboardingController`**

```java
package com.superpowers.test.onboarding;

import com.superpowers.test.onboarding.dto.OnboardingRequest;
import com.superpowers.test.onboarding.dto.OnboardingResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/user")
public class OnboardingController {

    private final OnboardingService onboardingService;

    public OnboardingController(OnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    @PutMapping(value = "/onboarding", consumes = "multipart/form-data")
    public ResponseEntity<OnboardingResponse> completeOnboarding(
            Authentication authentication,
            @RequestPart("profileData") @Valid OnboardingRequest profileData,
            @RequestPart("profilePicture") MultipartFile profilePicture,
            @RequestPart("resume") MultipartFile resume) {
        Long userId = Long.valueOf(authentication.getName());
        OnboardingResponse response = onboardingService.completeOnboarding(userId, profileData, profilePicture, resume);
        return ResponseEntity.ok(response);
    }
}
```

- [ ] **Step 2: Write the failing integration test covering AC1–20**

```java
package com.superpowers.test.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import com.superpowers.test.auth.JwtService;
import com.superpowers.test.user.User;
import com.superpowers.test.user.UserRepository;
import com.superpowers.test.user.UserStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class OnboardingControllerIntegrationTest {

    private static final String ONBOARDING_PATH = "/api/v1/user/onboarding";
    private static final String BUCKET_NAME = "onboarding-test-bucket";
    private static final String VALID_PROFILE_DATA = "{"
            + "\"name\":\"Vivek Khamar\","
            + "\"jobPreference\":\"FULL_TIME\","
            + "\"preferredJobFunctions\":[\"Backend Engineer\",\"Solution Architect\"],"
            + "\"preferredLocations\":[\"Mumbai\",\"Bangalore\"]}";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
            .withServices(LocalStackContainer.Service.S3);

    @BeforeAll
    static void createBucket() throws Exception {
        localstack.execInContainer("awslocal", "s3", "mb", "s3://" + BUCKET_NAME);
    }

    @DynamicPropertySource
    static void awsProperties(DynamicPropertyRegistry registry) {
        registry.add("app.aws.s3.bucket-name", () -> BUCKET_NAME);
        registry.add("app.aws.s3.region", () -> localstack.getRegion());
        registry.add(
                "app.aws.s3.endpoint-override",
                () -> localstack.getEndpointOverride(LocalStackContainer.Service.S3).toString());
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    private Long userId;
    private String validJwt;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        User user = userRepository.save(new User("onboarding-user@example.com", "hash", "Old Name"));
        userId = user.getId();
        validJwt = jwtService.generateToken(String.valueOf(userId), "USER");
    }

    private ByteArrayResource namedResource(byte[] bytes, String filename) {
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private ResponseEntity<String> putOnboarding(String jwt, String profileDataJson, byte[] pictureBytes,
            String pictureFilename, byte[] resumeBytes, String resumeFilename) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();

        HttpHeaders profileDataHeaders = new HttpHeaders();
        profileDataHeaders.setContentType(MediaType.APPLICATION_JSON);
        parts.add("profileData", new HttpEntity<>(profileDataJson, profileDataHeaders));
        parts.add("profilePicture", namedResource(pictureBytes, pictureFilename));
        parts.add("resume", namedResource(resumeBytes, resumeFilename));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (jwt != null) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);
        }

        return restTemplate.exchange(ONBOARDING_PATH, HttpMethod.PUT, new HttpEntity<>(parts, headers), String.class);
    }

    // AC1-6
    @Test
    void returns200AndPersistsOnboardingDataOnValidRequest() {
        ResponseEntity<String> response = putOnboarding(
                validJwt, VALID_PROFILE_DATA, "img-bytes".getBytes(), "photo.png", "resume-bytes".getBytes(), "resume.pdf");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("\"status\":\"success\"")
                .contains("User onboarding profile completed successfully.");

        User reloaded = userRepository.findById(userId).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("Vivek Khamar");
        assertThat(reloaded.getPreferredJobFunctions()).containsExactlyInAnyOrder("Backend Engineer", "Solution Architect");
        assertThat(reloaded.getPreferredLocations()).containsExactlyInAnyOrder("Mumbai", "Bangalore");
        assertThat(reloaded.getProfilePictureUrl()).isNotBlank();
        assertThat(reloaded.getResumeUrl()).isNotBlank();
        assertThat(reloaded.getStatus()).isEqualTo(UserStatus.ONBOARDING_COMPLETED);
    }

    // AC7-9
    @Test
    void returns400WhenJobPreferenceIsNotFullTimeOrPartTime() {
        String badProfileData = VALID_PROFILE_DATA.replace("FULL_TIME", "CONTRACT");

        ResponseEntity<String> response = putOnboarding(
                validJwt, badProfileData, "img-bytes".getBytes(), "photo.png", "resume-bytes".getBytes(), "resume.pdf");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    // AC10-11: oversized resume rejected, S3 untouched, no partial user update
    @Test
    void returns400WhenResumeExceeds5MbAndUserIsUnchanged() {
        byte[] oversizedResume = new byte[5 * 1024 * 1024 + 1];

        ResponseEntity<String> response = putOnboarding(
                validJwt, VALID_PROFILE_DATA, "img-bytes".getBytes(), "photo.png", oversizedResume, "resume.pdf");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"errorCode\":\"INVALID_FILE\"");

        User reloaded = userRepository.findById(userId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(UserStatus.REGISTERED);
        assertThat(reloaded.getResumeUrl()).isNull();
    }

    // AC10-11: wrong resume format rejected
    @Test
    void returns400WhenResumeIsWrongFormat() {
        ResponseEntity<String> response = putOnboarding(
                validJwt, VALID_PROFILE_DATA, "img-bytes".getBytes(), "photo.png", "not-a-pdf".getBytes(), "resume.txt");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"errorCode\":\"INVALID_FILE\"");
    }

    // AC17-20: no Authorization header/cookie at all
    @Test
    void returns401WhenNoAuthenticationIsPresent() {
        ResponseEntity<String> response = putOnboarding(
                null, VALID_PROFILE_DATA, "img-bytes".getBytes(), "photo.png", "resume-bytes".getBytes(), "resume.pdf");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("\"errorCode\":\"UNAUTHENTICATED\"");
    }

    // AC17-20: malformed/invalid token treated the same as no token
    @Test
    void returns401WhenTokenIsInvalid() {
        ResponseEntity<String> response = putOnboarding(
                "not-a-real-jwt", VALID_PROFILE_DATA, "img-bytes".getBytes(), "photo.png", "resume-bytes".getBytes(), "resume.pdf");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails (context or assertions) before the controller wiring is confirmed correct**

Run: `./mvnw -q test -Dtest=OnboardingControllerIntegrationTest`
Expected: If `OnboardingController` from Step 1 already compiles cleanly, some tests may already pass; run this step regardless to get a concrete baseline before declaring done, and fix any failing assertion (e.g. content-type header casing, JSON field names) by re-reading the exact behavior of `GlobalExceptionHandler`/`OnboardingService` — do not weaken the test to make it pass.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q test -Dtest=OnboardingControllerIntegrationTest`
Expected: `Tests run: 6, Failures: 0, Errors: 0`.

- [ ] **Step 5: Write the failing transactional-rollback integration test (AC12–16)**

`OnboardingControllerIntegrationTest` above proves the happy path and validation-rejection paths, but none of its tests force a real storage failure through the full HTTP stack to confirm the **500 status code and DB rollback** required by AC12–16 (`OnboardingServiceTest` in Task 6 proves the no-DB-write behavior at the unit level, but not the HTTP status). This needs a separate test class with its own Spring context, because it replaces the real `FileStorageService` bean with a mock — reusing the same context as the happy-path test would break that test's real-upload assertions.

```java
package com.superpowers.test.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.superpowers.test.auth.JwtService;
import com.superpowers.test.storage.FileStorageException;
import com.superpowers.test.storage.FileStorageService;
import com.superpowers.test.user.User;
import com.superpowers.test.user.UserRepository;
import com.superpowers.test.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class OnboardingRollbackIntegrationTest {

    private static final String ONBOARDING_PATH = "/api/v1/user/onboarding";
    private static final String VALID_PROFILE_DATA = "{"
            + "\"name\":\"Vivek Khamar\","
            + "\"jobPreference\":\"FULL_TIME\","
            + "\"preferredJobFunctions\":[\"Backend Engineer\"],"
            + "\"preferredLocations\":[\"Mumbai\"]}";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    @MockitoBean
    private FileStorageService fileStorageService;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    private Long userId;
    private String validJwt;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        User user = userRepository.save(new User("rollback-user@example.com", "hash", "Old Name"));
        userId = user.getId();
        validJwt = jwtService.generateToken(String.valueOf(userId), "USER");
    }

    // AC12-16: a storage failure rolls back the whole transaction and returns 500
    @Test
    void returns500AndLeavesUserUnchangedWhenFileStorageFails() {
        when(fileStorageService.upload(any(), any()))
                .thenThrow(new FileStorageException("simulated S3 timeout", new RuntimeException("timeout")));

        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        HttpHeaders profileDataHeaders = new HttpHeaders();
        profileDataHeaders.setContentType(MediaType.APPLICATION_JSON);
        parts.add("profileData", new HttpEntity<>(VALID_PROFILE_DATA, profileDataHeaders));
        parts.add("profilePicture", namedResource("img-bytes".getBytes(), "photo.png"));
        parts.add("resume", namedResource("resume-bytes".getBytes(), "resume.pdf"));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + validJwt);

        ResponseEntity<String> response = restTemplate.exchange(
                ONBOARDING_PATH, HttpMethod.PUT, new HttpEntity<>(parts, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).contains("\"errorCode\":\"ONBOARDING_FAILED\"");

        User reloaded = userRepository.findById(userId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(UserStatus.REGISTERED);
        assertThat(reloaded.getName()).isEqualTo("Old Name");
    }

    private ByteArrayResource namedResource(byte[] bytes, String filename) {
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./mvnw -q test -Dtest=OnboardingRollbackIntegrationTest`
Expected: `Tests run: 1, Failures: 0, Errors: 0`.

- [ ] **Step 7: Run the entire test suite**

Run: `./mvnw -q test`
Expected: `BUILD SUCCESS`, all prior tests (auth, signup, user, storage, onboarding) still green.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/superpowers/test/onboarding/OnboardingController.java src/test/java/com/superpowers/test/onboarding/OnboardingControllerIntegrationTest.java src/test/java/com/superpowers/test/onboarding/OnboardingRollbackIntegrationTest.java
git commit -m "$(cat <<'EOF'
feat(DEMO-3): add PUT /api/v1/user/onboarding endpoint

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: Full quality-gate pass (CLAUDE.md §6)

**Files:** None (verification only).

- [ ] **Step 1: Run the full test suite**

Run: `./mvnw -q test`
Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Run verify (integration tests + JaCoCo check)**

Run: `./mvnw -q verify`
Expected: `BUILD SUCCESS`. If JaCoCo's 80% line-coverage rule fails on any new class (most likely `S3ClientConfig`'s branch for the non-LocalStack credential path, or `JwtAuthenticationFilter`'s malformed-cookie edge cases), add the missing unit test case(s) for that specific branch and re-run — do not lower the threshold in `pom.xml`.

- [ ] **Step 3: Run Checkstyle**

Run: `./mvnw -q checkstyle:check`
Expected: `BUILD SUCCESS`. Fix any reported unused import / line-length / missing-EOF-newline violation directly in the flagged file.

- [ ] **Step 4: Run SpotBugs**

Run: `./mvnw -q spotbugs:check`
Expected: `BUILD SUCCESS`. If `User`'s new getters/setters trip `EI_EXPOSE_REP`/`EI_EXPOSE_REP2`, confirm the defensive-copy pattern from Task 2 Step 5 was applied exactly as written (unmodifiable view on get, defensive copy on set) rather than adding a new spotbugs-exclude entry.

- [ ] **Step 5: If all four gates pass, this feature is ready for the finishing-a-development-branch skill.**

No commit in this task unless a fix was needed in Steps 2–4, in which case commit that fix with a message like:

```bash
git add -A
git commit -m "$(cat <<'EOF'
fix(DEMO-3): satisfy quality gates (coverage/checkstyle/spotbugs)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```
