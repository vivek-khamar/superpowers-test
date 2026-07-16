# DEMO-4: Admin User Directory Endpoint — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `GET /api/v1/admin/users` — a `ROLE_ADMIN`-only, paginated, filterable directory of all users (both `ROLE_USER` and `ROLE_ADMIN`), supporting an optional exact `role` filter and an optional case-insensitive `search` over name/email.

**Architecture:** `AdminUserController` → `AdminUserService` (builds a JPA `Specification<User>` from the optional filters, maps the resulting `Page<User>` to a hand-written pagination envelope) → `UserRepository` (extended with `JpaSpecificationExecutor<User>`). Authorization is enforced at the `SecurityConfig` filter-chain level (`hasRole("ADMIN")`), with a new `RestAccessDeniedHandler` producing an RFC 7807 403 body (today a 403 would fall through to Spring Security's default handler, breaking the project-wide `problem+json` contract).

**Tech Stack:** Spring Boot 4.1 (Web, Security, Data JPA, Validation), Flyway, PostgreSQL — all already present. No new dependencies.

## Global Constraints

- Branch: `feat/DEMO-4-implement-admin-dashboard` (already checked out in the worktree).
- Package root: `com.superpowers.test` (NOT `com.example.api` — stale sprint-context boilerplate text).
- Controller → Service → Repository, no exceptions. Business logic only in service-equivalent classes (this codebase's convention: services live directly in the domain package, e.g. `com.superpowers.test.admin.AdminUserService`).
- `@Valid` + Bean Validation on every request DTO. (This endpoint has no request body — query params are validated via type binding + the existing `GlobalExceptionHandler`.)
- RFC 7807 `application/problem+json` for every error response, via the existing `GlobalExceptionHandler` (`com.superpowers.test.auth.exception.GlobalExceptionHandler`) — extend it, don't create a second handler.
- SLF4J `log.*` only — never `System.out.println`. Never log passwords, tokens, or PII.
- Only modify files under `src/main/java/com/superpowers/test/**` and `src/test/java/com/superpowers/test/**`, plus new Flyway migrations. Do **not** touch `V1__create_users_table.sql`, `V2__add_users_created_updated_at.sql`, or `V3__add_onboarding_fields_to_users.sql`.
- Unit tests: JUnit 5 + Mockito + AssertJ (not `assertEquals`). Integration tests: `@SpringBootTest` + Testcontainers Postgres + `TestRestTemplate`, following `AuthControllerIntegrationTest` / `OnboardingControllerIntegrationTest` exactly.
- Checkstyle: 140-char line limit, no unused/star imports, newline at EOF (`checkstyle.xml`). SpotBugs: effort Max, threshold Medium — new DTO records are already covered by the existing `~.*\.dto\..*` exclusion in `spotbugs-exclude.xml` for `EI_EXPOSE_REP`/`EI_EXPOSE_REP2`; no new exclude entries should be needed.
- JaCoCo: ≥80% line coverage on new code.

---

### Task 1: `UserRepository` specification support + Flyway index migration

**Files:**
- Modify: `src/main/java/com/superpowers/test/user/UserRepository.java`
- Create: `src/main/resources/db/migration/V4__add_indexes_for_admin_user_search.sql`
- Modify: `src/test/java/com/superpowers/test/user/UserRepositoryTest.java`

**Interfaces:**
- Produces: `UserRepository` now also exposes `findAll(Specification<User>, Pageable)` (from `JpaSpecificationExecutor<User>`), consumed by `AdminUserService` (Task 2).

- [ ] **Step 1: Write the failing repository test**

Add this test method to `src/test/java/com/superpowers/test/user/UserRepositoryTest.java`, right after `persistsOnboardingFieldsIncludingListCollections`:

```java
    @Test
    void supportsSpecificationBasedQueriesWithPagination() {
        userRepository.save(new User("spec-a@example.com", "hash", "Spec User A", "ADMIN"));
        userRepository.save(new User("spec-b@example.com", "hash", "Spec User B", "USER"));

        Specification<User> adminOnly = (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("role"), "ADMIN");
        Page<User> result = userRepository.findAll(adminOnly, PageRequest.of(0, 10));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getEmail()).isEqualTo("spec-a@example.com");
    }
```

Add these imports to the same file, alongside the existing imports:

```java
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `./mvnw -q test -Dtest=UserRepositoryTest`
Expected: Compilation error — `cannot find symbol: method findAll(Specification,PageRequest)` (or similar), confirming `UserRepository` doesn't yet support specification queries.

- [ ] **Step 3: Extend `UserRepository`**

Replace the full contents of `src/main/java/com/superpowers/test/user/UserRepository.java` with:

```java
package com.superpowers.test.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmailIgnoreCase(String email);
}
```

- [ ] **Step 4: Create the Flyway migration**

```sql
CREATE INDEX idx_users_role ON users (role);
CREATE INDEX idx_users_name ON users (name);
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -q test -Dtest=UserRepositoryTest`
Expected: `Tests run: 7, Failures: 0, Errors: 0` (6 existing + 1 new), Flyway log shows migration `4 - add indexes for admin user search` applied.

- [ ] **Step 6: Run full test suite to check nothing else broke**

Run: `./mvnw -q test`
Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/superpowers/test/user/UserRepository.java src/main/resources/db/migration/V4__add_indexes_for_admin_user_search.sql src/test/java/com/superpowers/test/user/UserRepositoryTest.java
git commit -m "$(cat <<'EOF'
feat(DEMO-4): support specification queries on UserRepository

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Admin DTOs, role enum, and `AdminUserService`

**Files:**
- Create: `src/main/java/com/superpowers/test/admin/AdminUserRole.java`
- Create: `src/main/java/com/superpowers/test/admin/dto/AdminUserResponse.java`
- Create: `src/main/java/com/superpowers/test/admin/dto/PageableInfo.java`
- Create: `src/main/java/com/superpowers/test/admin/dto/AdminUserPageResponse.java`
- Create: `src/main/java/com/superpowers/test/admin/AdminUserService.java`
- Create: `src/test/java/com/superpowers/test/admin/AdminUserServiceTest.java`

**Interfaces:**
- Consumes: `UserRepository.findAll(Specification<User>, Pageable)` (Task 1); `User` getters (`getId`, `getName`, `getEmail`, `getRole`, `getStatus`, `getCreatedAt` — all pre-existing).
- Produces: `AdminUserRole` enum (`ROLE_USER`, `ROLE_ADMIN`). `AdminUserService.listUsers(int page, int size, AdminUserRole role, String search)` returning `AdminUserPageResponse`. Consumed by `AdminUserController` (Task 4).

- [ ] **Step 1: Write the failing service unit test**

```java
package com.superpowers.test.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.superpowers.test.admin.dto.AdminUserPageResponse;
import com.superpowers.test.user.User;
import com.superpowers.test.user.UserRepository;
import com.superpowers.test.user.UserStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

class AdminUserServiceTest {

    private UserRepository userRepository;
    private AdminUserService adminUserService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        adminUserService = new AdminUserService(userRepository);
    }

    private User userWith(Long id, String name, String email, String role, UserStatus status) {
        User user = new User(email, "hash", name, role);
        ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(user, "status", status);
        ReflectionTestUtils.setField(user, "createdAt", Instant.parse("2026-03-15T10:00:00Z"));
        return user;
    }

    // AC1-5: both role types come back unified in one response envelope
    @Test
    void mapsUsersOfBothRolesIntoTheResponseEnvelope() {
        User standardUser = userWith(1L, "Jane Doe", "jane.doe@example.com", "USER", UserStatus.REGISTERED);
        User adminUser = userWith(2L, "Vivek Khamar", "vivekhamar@gmail.com", "ADMIN", UserStatus.REGISTERED);
        Page<User> page = new PageImpl<>(List.of(standardUser, adminUser), PageRequest.of(0, 20), 2);
        when(userRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        AdminUserPageResponse response = adminUserService.listUsers(0, 20, null, null);

        assertThat(response.content()).hasSize(2);
        assertThat(response.content().get(0).id()).isEqualTo("1");
        assertThat(response.content().get(0).role()).isEqualTo("ROLE_USER");
        assertThat(response.content().get(1).role()).isEqualTo("ROLE_ADMIN");
        assertThat(response.content().get(1).createdAt()).isEqualTo(Instant.parse("2026-03-15T10:00:00Z"));
        assertThat(response.pageable().pageNumber()).isZero();
        assertThat(response.pageable().pageSize()).isEqualTo(20);
        assertThat(response.pageable().totalElements()).isEqualTo(2);
        assertThat(response.pageable().totalPages()).isEqualTo(1);
    }

    // Decision 8: default sort must be createdAt descending
    @Test
    void requestsPageableSortedByCreatedAtDescending() {
        Page<User> emptyPage = new PageImpl<>(List.of(), PageRequest.of(1, 5), 0);
        when(userRepository.findAll(any(Specification.class), eq(PageRequest.of(1, 5, Sort.by("createdAt").descending()))))
                .thenReturn(emptyPage);

        AdminUserPageResponse response = adminUserService.listUsers(1, 5, null, null);

        assertThat(response.content()).isEmpty();
        assertThat(response.pageable().pageNumber()).isEqualTo(1);
        assertThat(response.pageable().pageSize()).isEqualTo(5);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `./mvnw -q test -Dtest=AdminUserServiceTest`
Expected: Compilation error — `cannot find symbol: class AdminUserService` (and the DTO classes it references).

- [ ] **Step 3: Create the `AdminUserRole` enum**

```java
package com.superpowers.test.admin;

public enum AdminUserRole {
    ROLE_USER,
    ROLE_ADMIN
}
```

- [ ] **Step 4: Create the `AdminUserResponse` record**

```java
package com.superpowers.test.admin.dto;

import java.time.Instant;

public record AdminUserResponse(String id, String name, String email, String role, String status, Instant createdAt) {
}
```

- [ ] **Step 5: Create the `PageableInfo` record**

```java
package com.superpowers.test.admin.dto;

public record PageableInfo(int pageNumber, int pageSize, long totalElements, int totalPages) {
}
```

- [ ] **Step 6: Create the `AdminUserPageResponse` record**

```java
package com.superpowers.test.admin.dto;

import java.util.List;

public record AdminUserPageResponse(List<AdminUserResponse> content, PageableInfo pageable) {
}
```

- [ ] **Step 7: Create `AdminUserService`**

```java
package com.superpowers.test.admin;

import com.superpowers.test.admin.dto.AdminUserPageResponse;
import com.superpowers.test.admin.dto.AdminUserResponse;
import com.superpowers.test.admin.dto.PageableInfo;
import com.superpowers.test.user.User;
import com.superpowers.test.user.UserRepository;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

@Service
public class AdminUserService {

    private static final String ROLE_PREFIX = "ROLE_";

    private final UserRepository userRepository;

    public AdminUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public AdminUserPageResponse listUsers(int page, int size, AdminUserRole role, String search) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Specification<User> spec = buildSpecification(role, search);

        Page<User> result = userRepository.findAll(spec, pageable);

        List<AdminUserResponse> content = result.getContent().stream()
                .map(this::toResponse)
                .toList();

        PageableInfo pageableInfo = new PageableInfo(
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());

        return new AdminUserPageResponse(content, pageableInfo);
    }

    private Specification<User> buildSpecification(AdminUserRole role, String search) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (role != null) {
                String dbRole = role.name().substring(ROLE_PREFIX.length());
                predicates.add(criteriaBuilder.equal(root.get("role"), dbRole));
            }

            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.toLowerCase(Locale.ROOT) + "%";
                Predicate nameMatch = criteriaBuilder.like(criteriaBuilder.lower(root.get("name")), pattern);
                Predicate emailMatch = criteriaBuilder.like(criteriaBuilder.lower(root.get("email")), pattern);
                predicates.add(criteriaBuilder.or(nameMatch, emailMatch));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }

    private AdminUserResponse toResponse(User user) {
        return new AdminUserResponse(
                String.valueOf(user.getId()),
                user.getName(),
                user.getEmail(),
                ROLE_PREFIX + user.getRole(),
                user.getStatus().name(),
                user.getCreatedAt());
    }
}
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `./mvnw -q test -Dtest=AdminUserServiceTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0`.

- [ ] **Step 9: Run full test suite to check nothing else broke**

Run: `./mvnw -q test`
Expected: `BUILD SUCCESS`.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/superpowers/test/admin/ src/test/java/com/superpowers/test/admin/
git commit -m "$(cat <<'EOF'
feat(DEMO-4): add AdminUserService with role/search filtering

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Authorization enforcement — `RestAccessDeniedHandler`, `SecurityConfig`, `GlobalExceptionHandler`

**Context:** `SecurityConfig` currently has no role-gated route and no `AccessDeniedHandler` — an authenticated non-admin hitting a `hasRole(...)`-protected path today would get Spring Security's default (non-RFC-7807) 403. This task is a real prerequisite for AC11–14, not pre-existing infrastructure.

**Files:**
- Create: `src/main/java/com/superpowers/test/auth/RestAccessDeniedHandler.java`
- Create: `src/test/java/com/superpowers/test/auth/RestAccessDeniedHandlerTest.java`
- Modify: `src/main/java/com/superpowers/test/config/SecurityConfig.java`
- Modify: `src/main/java/com/superpowers/test/auth/exception/GlobalExceptionHandler.java`

**Interfaces:**
- Produces: after this task, a request to `/api/v1/admin/**` from an authenticated non-`ADMIN` principal gets HTTP 403 with body `{"errorCode":"ACCESS_DENIED",...}`; an invalid enum-typed query parameter anywhere in the app gets HTTP 400 with `{"errorCode":"INVALID_QUERY_PARAMETER",...}`. Both consumed/verified by `AdminUserControllerIntegrationTest` (Task 4).

- [ ] **Step 1: Write the failing `RestAccessDeniedHandler` unit test**

```java
package com.superpowers.test.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

class RestAccessDeniedHandlerTest {

    @Test
    void writes403ProblemJsonBody() throws Exception {
        RestAccessDeniedHandler handler = new RestAccessDeniedHandler();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(new MockHttpServletRequest(), response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getContentAsString()).contains("\"errorCode\":\"ACCESS_DENIED\"");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `./mvnw -q test -Dtest=RestAccessDeniedHandlerTest`
Expected: Compilation error — `cannot find symbol: class RestAccessDeniedHandler`.

- [ ] **Step 3: Create `RestAccessDeniedHandler`**

```java
package com.superpowers.test.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(
                "{\"errorCode\":\"ACCESS_DENIED\",\"message\":\"You do not have permission to access this resource.\"}");
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q test -Dtest=RestAccessDeniedHandlerTest`
Expected: `Tests run: 1, Failures: 0, Errors: 0`.

- [ ] **Step 5: Wire the handler and the new admin route into `SecurityConfig`**

Replace the full contents of `src/main/java/com/superpowers/test/config/SecurityConfig.java` with:

```java
package com.superpowers.test.config;

import com.superpowers.test.auth.JwtAuthenticationFilter;
import com.superpowers.test.auth.JwtService;
import com.superpowers.test.auth.RestAccessDeniedHandler;
import com.superpowers.test.auth.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, JwtService jwtService, RestAuthenticationEntryPoint restAuthenticationEntryPoint,
            RestAccessDeniedHandler restAccessDeniedHandler) {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(restAuthenticationEntryPoint)
                        .accessDeniedHandler(restAccessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/signup", "/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

- [ ] **Step 6: Add the `MethodArgumentTypeMismatchException` handler to `GlobalExceptionHandler`**

Add this import to `src/main/java/com/superpowers/test/auth/exception/GlobalExceptionHandler.java`, alongside the existing imports:

```java
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
```

Add this method right after `handleValidation`, before `handleEmailAlreadyExists`:

```java
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String detail = "Query parameter '" + ex.getName() + "' has an invalid value.";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Invalid Query Parameter");
        problem.setProperty(ERROR_CODE_PROPERTY, "INVALID_QUERY_PARAMETER");
        problem.setProperty(MESSAGE_PROPERTY, detail);
        return problem;
    }
```

- [ ] **Step 7: Run the full test suite to confirm existing flows still work**

Run: `./mvnw -q test`
Expected: `BUILD SUCCESS` — `AuthControllerIntegrationTest`, `SignupControllerIntegrationTest`, and `OnboardingControllerIntegrationTest` still pass unmodified (none of their paths match `/api/v1/admin/**`).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/superpowers/test/auth/RestAccessDeniedHandler.java src/test/java/com/superpowers/test/auth/RestAccessDeniedHandlerTest.java src/main/java/com/superpowers/test/config/SecurityConfig.java src/main/java/com/superpowers/test/auth/exception/GlobalExceptionHandler.java
git commit -m "$(cat <<'EOF'
feat(DEMO-4): enforce ROLE_ADMIN on /api/v1/admin with RFC7807 403/400

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: `AdminUserController` + end-to-end integration tests

**Files:**
- Create: `src/main/java/com/superpowers/test/admin/AdminUserController.java`
- Create: `src/test/java/com/superpowers/test/admin/AdminUserControllerIntegrationTest.java`

**Interfaces:**
- Consumes: `AdminUserService.listUsers(int, int, AdminUserRole, String)` (Task 2); `SecurityConfig`'s `hasRole("ADMIN")` gate and `RestAccessDeniedHandler`/`RestAuthenticationEntryPoint` (Task 3); `JwtService.generateToken(String, String)` (pre-existing, used by the test to mint tokens for a seeded admin/standard user).

- [ ] **Step 1: Write the failing controller integration test**

```java
package com.superpowers.test.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.superpowers.test.auth.JwtService;
import com.superpowers.test.user.User;
import com.superpowers.test.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AdminUserControllerIntegrationTest {

    private static final String USERS_PATH = "/api/v1/admin/users";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    private String adminJwt;
    private String standardUserJwt;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        User standardUser = userRepository.save(new User("jane.doe@example.com", "hash", "Jane Doe", "USER"));
        User adminUser = userRepository.save(new User("vivekhamar@gmail.com", "hash", "Vivek Khamar", "ADMIN"));
        standardUserJwt = jwtService.generateToken(String.valueOf(standardUser.getId()), "USER");
        adminJwt = jwtService.generateToken(String.valueOf(adminUser.getId()), "ADMIN");
    }

    private ResponseEntity<String> getUsers(String jwt, String queryString) {
        HttpHeaders headers = new HttpHeaders();
        if (jwt != null) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);
        }
        String path = queryString == null ? USERS_PATH : USERS_PATH + "?" + queryString;
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    // AC1-5
    @Test
    void returns200WithBothRolesUnifiedForAnAdminCaller() {
        ResponseEntity<String> response = getUsers(adminJwt, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("jane.doe@example.com").contains("vivekhamar@gmail.com");
        assertThat(response.getBody()).contains("\"pageNumber\":0").contains("\"totalElements\":2");
    }

    // AC7-8
    @Test
    void filtersToOnlyAdminRoleWhenRoleParamIsSupplied() {
        ResponseEntity<String> response = getUsers(adminJwt, "role=ROLE_ADMIN");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("vivekhamar@gmail.com").doesNotContain("jane.doe@example.com");
    }

    // AC9-10
    @Test
    void matchesSearchAgainstNameOrEmailCaseInsensitively() {
        ResponseEntity<String> response = getUsers(adminJwt, "search=JANE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("jane.doe@example.com").doesNotContain("vivekhamar@gmail.com");
    }

    // AC11-14: no authentication at all
    @Test
    void returns401WhenNoAuthenticationIsPresent() {
        ResponseEntity<String> response = getUsers(null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("\"errorCode\":\"UNAUTHENTICATED\"");
    }

    // AC11-14: authenticated but wrong role
    @Test
    void returns403ForAuthenticatedStandardUser() {
        ResponseEntity<String> response = getUsers(standardUserJwt, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("\"errorCode\":\"ACCESS_DENIED\"");
    }

    // Decision 3: invalid role query param -> 400, not a 500 or silent ignore
    @Test
    void returns400ForAnInvalidRoleQueryParam() {
        ResponseEntity<String> response = getUsers(adminJwt, "role=BOGUS");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"errorCode\":\"INVALID_QUERY_PARAMETER\"");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q test -Dtest=AdminUserControllerIntegrationTest`
Expected: Compilation error or all requests returning 404 (no controller mapped to `/api/v1/admin/users` yet).

- [ ] **Step 3: Create `AdminUserController`**

```java
package com.superpowers.test.admin;

import com.superpowers.test.admin.dto.AdminUserPageResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping("/users")
    public ResponseEntity<AdminUserPageResponse> listUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) AdminUserRole role,
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(adminUserService.listUsers(page, size, role, search));
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q test -Dtest=AdminUserControllerIntegrationTest`
Expected: `Tests run: 6, Failures: 0, Errors: 0`.

- [ ] **Step 5: Run the full test suite**

Run: `./mvnw -q test`
Expected: `BUILD SUCCESS`, all tests across the project pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/superpowers/test/admin/AdminUserController.java src/test/java/com/superpowers/test/admin/AdminUserControllerIntegrationTest.java
git commit -m "$(cat <<'EOF'
feat(DEMO-4): add GET /api/v1/admin/users endpoint

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## After all tasks

Run the full CLAUDE.md quality gate sequence in order (`./mvnw test`, `./mvnw verify`, `./mvnw checkstyle:check`, `./mvnw spotbugs:check`, SonarQube scan), fixing forward before advancing to the next gate, then proceed to PR creation per CLAUDE.md §8.
