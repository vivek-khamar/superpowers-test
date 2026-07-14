# DEMO-1 Signup Endpoint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `POST /api/v1/auth/signup` so new users can register with a hashed password, duplicate-email rejection, input validation, and per-IP rate limiting, per `docs/superpowers/specs/2026-07-10-demo-1-signup-design.md`.

**Architecture:** `SignupController` (Controller) → `SignupService` (Service) → `UserRepository` (Spring Data JPA) + `PasswordEncoder` (BCrypt). `SignupRateLimitFilter` (servlet filter, `Ordered.HIGHEST_PRECEDENCE`) guards the signup path before it reaches Spring Security/MVC. `GlobalExceptionHandler` maps domain/validation exceptions to RFC 7807 `ProblemDetail` responses.

**Tech Stack:** Java 21, Spring Boot 4.1 (Web, Security, Data JPA, Validation), Flyway + PostgreSQL, JUnit 5 + Mockito + AssertJ, Testcontainers Postgres, Checkstyle/SpotBugs/JaCoCo.

## Global Constraints

- Controller → Service → Repository layering only; business logic lives only in `com.superpowers.test.auth` service classes (CLAUDE.md §4).
- `@Valid` + Bean Validation on every request DTO (CLAUDE.md §4).
- All error responses are `application/problem+json` (RFC 7807) (CLAUDE.md §4).
- Config via `@ConfigurationProperties` only — no bare `@Value` for tunables (CLAUDE.md §4).
- SLF4J `log.*` only, never `System.out.println`; never log passwords/tokens/PII (CLAUDE.md §4).
- Unit tests: JUnit 5 + Mockito, every service method. Integration: `@SpringBootTest` + Testcontainers, every endpoint. Coverage ≥80% line on new code (JaCoCo). Assertions via AssertJ, not `assertEquals` (CLAUDE.md §5).
- Commit format: `type(DEMO-1): summary`, first line ≤72 chars (CLAUDE.md §3).
- Do not touch existing Flyway migrations — only add new ones (CLAUDE.md §7).
- Target table: `users`; public user ID shape: `usr_{id}`; password regex: `^(?=.*[A-Z])(?=.*[0-9])(?=.*[^A-Za-z0-9]).{8,}$`; rate limit: 10 requests/60s per IP, in-memory (see design doc decisions 1-5).

---

### Task 1: Build dependencies and quality-gate plugins

**Files:**
- Modify: `pom.xml`
- Create: `checkstyle.xml`
- Create: `spotbugs-exclude.xml`

**Interfaces:**
- Produces: Maven dependencies (`spring-boot-starter-web`, `-security`, `-data-jpa`, `-validation`, `flyway-core`, `flyway-database-postgresql`, `postgresql`, and test-scope `spring-boot-starter-data-jpa-test`, `spring-boot-starter-flyway-test`, `spring-boot-resttestclient`, `spring-boot-restclient`, `spring-boot-testcontainers`, `testcontainers-junit-jupiter`, `testcontainers-postgresql`) and plugins (`maven-checkstyle-plugin`, `spotbugs-maven-plugin`, `jacoco-maven-plugin`) available to every later task.

- [ ] **Step 1: Replace `pom.xml` with the full dependency/plugin set**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
	xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
	<modelVersion>4.0.0</modelVersion>
	<parent>
		<groupId>org.springframework.boot</groupId>
		<artifactId>spring-boot-starter-parent</artifactId>
		<version>4.1.0</version>
		<relativePath/> <!-- lookup parent from repository -->
	</parent>
	<groupId>com.superpowers</groupId>
	<artifactId>test</artifactId>
	<version>0.0.1-SNAPSHOT</version>
	<name/>
	<description/>
	<url/>
	<licenses>
		<license/>
	</licenses>
	<developers>
		<developer/>
	</developers>
	<scm>
		<connection/>
		<developerConnection/>
		<tag/>
		<url/>
	</scm>
	<properties>
		<java.version>21</java.version>
	</properties>
	<dependencies>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-web</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-data-jpa</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-validation</artifactId>
		</dependency>
		<dependency>
			<groupId>org.flywaydb</groupId>
			<artifactId>flyway-core</artifactId>
		</dependency>
		<dependency>
			<groupId>org.flywaydb</groupId>
			<artifactId>flyway-database-postgresql</artifactId>
		</dependency>
		<dependency>
			<groupId>org.postgresql</groupId>
			<artifactId>postgresql</artifactId>
			<scope>runtime</scope>
		</dependency>

		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-data-jpa-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-flyway-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-resttestclient</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-restclient</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-testcontainers</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>testcontainers-junit-jupiter</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>testcontainers-postgresql</artifactId>
			<scope>test</scope>
		</dependency>
	</dependencies>

	<build>
		<plugins>
			<plugin>
				<groupId>org.springframework.boot</groupId>
				<artifactId>spring-boot-maven-plugin</artifactId>
			</plugin>
			<plugin>
				<groupId>org.apache.maven.plugins</groupId>
				<artifactId>maven-checkstyle-plugin</artifactId>
				<version>3.3.1</version>
				<configuration>
					<configLocation>checkstyle.xml</configLocation>
					<encoding>UTF-8</encoding>
					<consoleOutput>true</consoleOutput>
					<failOnViolation>true</failOnViolation>
					<violationSeverity>warning</violationSeverity>
					<includeTestSourceDirectory>true</includeTestSourceDirectory>
				</configuration>
			</plugin>
			<plugin>
				<groupId>com.github.spotbugs</groupId>
				<artifactId>spotbugs-maven-plugin</artifactId>
				<version>4.10.2.0</version>
				<configuration>
					<effort>Max</effort>
					<threshold>Medium</threshold>
					<excludeFilterFile>spotbugs-exclude.xml</excludeFilterFile>
				</configuration>
			</plugin>
			<plugin>
				<groupId>org.jacoco</groupId>
				<artifactId>jacoco-maven-plugin</artifactId>
				<version>0.8.14</version>
				<executions>
					<execution>
						<id>prepare-agent</id>
						<goals>
							<goal>prepare-agent</goal>
						</goals>
					</execution>
					<execution>
						<id>report</id>
						<phase>test</phase>
						<goals>
							<goal>report</goal>
						</goals>
					</execution>
					<execution>
						<id>check</id>
						<phase>verify</phase>
						<goals>
							<goal>check</goal>
						</goals>
						<configuration>
							<rules>
								<rule>
									<element>BUNDLE</element>
									<limits>
										<limit>
											<counter>LINE</counter>
											<value>COVEREDRATIO</value>
											<minimum>0.80</minimum>
										</limit>
									</limits>
								</rule>
							</rules>
						</configuration>
					</execution>
				</executions>
			</plugin>
		</plugins>
	</build>

</project>
```

- [ ] **Step 2: Create `checkstyle.xml`**

```xml
<?xml version="1.0"?>
<!DOCTYPE module PUBLIC
        "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
        "https://checkstyle.org/dtds/configuration_1_3.dtd">
<module name="Checker">
    <module name="TreeWalker">
        <module name="UnusedImports"/>
        <module name="AvoidStarImport"/>
        <module name="RedundantImport"/>
        <module name="EqualsHashCode"/>
    </module>
    <module name="LineLength">
        <property name="max" value="140"/>
    </module>
    <module name="NewlineAtEndOfFile"/>
</module>
```

- [ ] **Step 3: Create `spotbugs-exclude.xml`**

```xml
<FindBugsFilter>
    <Match>
        <Class name="~.*\.dto\..*"/>
        <Bug pattern="EI_EXPOSE_REP"/>
    </Match>
    <Match>
        <!-- Constructor-injected @ConfigurationProperties beans are Spring-managed
             config holders, not externally-mutable attacker-controlled state. -->
        <Class name="com.superpowers.test.auth.ratelimit.SignupRateLimitFilter"/>
        <Bug pattern="EI_EXPOSE_REP2"/>
    </Match>
</FindBugsFilter>
```

- [ ] **Step 4: Verify the build still compiles with no source changes yet**

Run: `./mvnw -q compile`
Expected: `BUILD SUCCESS` (no output on success with `-q`)

- [ ] **Step 5: Commit**

```bash
git add pom.xml checkstyle.xml spotbugs-exclude.xml
git commit -m "build(DEMO-1): add security/JPA deps and quality-gate plugins"
```

---

### Task 2: Users table migration and datasource config

**Files:**
- Create: `src/main/resources/db/migration/V1__create_users_table.sql`
- Modify: `src/main/resources/application.properties`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `users` table (`id`, `email`, `password_hash`, `name`, `created_at`, `updated_at`) with a case-insensitive unique index on `email`, that Task 3's `User` entity maps onto.

- [ ] **Step 1: Create the migration**

```sql
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX ux_users_email ON users (LOWER(email));
```

- [ ] **Step 2: Update `application.properties`**

```properties
spring.application.name=test

spring.datasource.url=jdbc:postgresql://localhost:5432/superpowers_test
spring.datasource.username=superpowers_test
spring.datasource.password=superpowers_test

spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false

spring.flyway.locations=classpath:db/migration

app.rate-limit.signup.max-requests=10
app.rate-limit.signup.window-seconds=60
```

- [ ] **Step 3: Verify the build still compiles**

Run: `./mvnw -q compile`
Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/db/migration/V1__create_users_table.sql src/main/resources/application.properties
git commit -m "build(DEMO-1): add users table migration and datasource config"
```

---

### Task 3: User entity and repository

**Files:**
- Create: `src/main/java/com/superpowers/test/user/User.java`
- Create: `src/main/java/com/superpowers/test/user/UserRepository.java`
- Test: `src/test/java/com/superpowers/test/user/UserRepositoryTest.java`

**Interfaces:**
- Consumes: `users` table from Task 2.
- Produces: `User(String email, String passwordHash, String name)` constructor; `User#getId(): Long`, `#getEmail(): String`, `#getPasswordHash(): String`, `#getName(): String`, `#getCreatedAt(): Instant`, `#getUpdatedAt(): Instant`. `UserRepository extends JpaRepository<User, Long>` with `findByEmailIgnoreCase(String): Optional<User>` — both consumed by Task 6's `SignupService`.

- [ ] **Step 1: Write the failing test**

```java
package com.superpowers.test.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@DataJpaTest
class UserRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private UserRepository userRepository;

    @Test
    void findsUserByEmailCaseInsensitively() {
        userRepository.save(new User("user@example.com", "hash", "Alex Doe"));

        assertThat(userRepository.findByEmailIgnoreCase("USER@EXAMPLE.COM")).isPresent();
        assertThat(userRepository.findByEmailIgnoreCase("nobody@example.com")).isEmpty();
    }

    @Test
    void populatesGeneratedIdAndTimestampsOnSave() {
        User saved = userRepository.save(new User("fresh@example.com", "hash", "Fresh User"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void rejectsDuplicateEmailAtTheDatabaseLevelRegardlessOfCase() {
        userRepository.saveAndFlush(new User("dup@example.com", "hash", "First"));

        assertThatThrownBy(() -> userRepository.saveAndFlush(new User("DUP@example.com", "hash", "Second")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails (classes don't exist yet)**

Run: `./mvnw -q test -Dtest=UserRepositoryTest`
Expected: FAIL — compilation error, `User`/`UserRepository` not found

- [ ] **Step 3: Write `User.java`**

```java
package com.superpowers.test.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String name;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {
    }

    public User(String email, String passwordHash, String name) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
```

- [ ] **Step 4: Write `UserRepository.java`**

```java
package com.superpowers.test.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmailIgnoreCase(String email);
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=UserRepositoryTest`
Expected: PASS (3 tests, 0 failures) — requires Docker for Testcontainers Postgres

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/superpowers/test/user src/test/java/com/superpowers/test/user
git commit -m "feat(DEMO-1): add users table migration, User entity and repository"
```

---

### Task 4: Signup request/response DTOs with validation

**Files:**
- Create: `src/main/java/com/superpowers/test/auth/dto/SignupRequest.java`
- Create: `src/main/java/com/superpowers/test/auth/dto/SignupResponse.java`
- Test: `src/test/java/com/superpowers/test/auth/dto/SignupRequestValidationTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks (plain records).
- Produces: `SignupRequest(String name, String email, String password)` record consumed by Task 8's `SignupController`; `SignupResponse(String status, String message, String userId)` with static factory `SignupResponse.success(String userId): SignupResponse`, consumed by Task 8.

- [ ] **Step 1: Write the failing test**

```java
package com.superpowers.test.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SignupRequestValidationTest {

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

    @Test
    void acceptsAWellFormedRequest() {
        SignupRequest request = new SignupRequest("Vivek Khamar", "user@example.com", "SecurePassword123!");

        Set<ConstraintViolation<SignupRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void rejectsBlankName(String blankName) {
        SignupRequest request = new SignupRequest(blankName, "user@example.com", "SecurePassword123!");

        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    void rejectsNullRequiredFields() {
        SignupRequest request = new SignupRequest(null, null, null);

        assertThat(validator.validate(request)).hasSize(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-email", "missing-at-sign.com", "user@", "@example.com"})
    void rejectsMalformedEmail(String badEmail) {
        SignupRequest request = new SignupRequest("Vivek Khamar", badEmail, "SecurePassword123!");

        assertThat(validator.validate(request)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "short1!",          // < 8 chars
            "alllowercase123!", // no uppercase
            "NoDigitsHere!",    // no digit
            "NoSpecial123"      // no special char
    })
    void rejectsPasswordsFailingComplexityRules(String weakPassword) {
        SignupRequest request = new SignupRequest("Vivek Khamar", "user@example.com", weakPassword);

        assertThat(validator.validate(request)).isNotEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=SignupRequestValidationTest`
Expected: FAIL — compilation error, `SignupRequest` not found

- [ ] **Step 3: Write `SignupRequest.java`**

```java
package com.superpowers.test.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SignupRequest(

        @NotBlank(message = "name is required")
        String name,

        @NotBlank(message = "email is required")
        @Email(message = "email must be a well-formed address")
        @Pattern(
                regexp = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$",
                message = "email must be a well-formed address")
        String email,

        @NotBlank(message = "password is required")
        @Pattern(
                regexp = "^(?=.*[A-Z])(?=.*[0-9])(?=.*[^A-Za-z0-9]).{8,}$",
                message = "password must be at least 8 characters and include 1 uppercase letter, "
                        + "1 number, and 1 special character")
        String password) {
}
```

- [ ] **Step 4: Write `SignupResponse.java`**

```java
package com.superpowers.test.auth.dto;

public record SignupResponse(String status, String message, String userId) {

    public static SignupResponse success(String userId) {
        return new SignupResponse("success", "User registered successfully.", userId);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=SignupRequestValidationTest`
Expected: PASS (6 tests, 0 failures)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/superpowers/test/auth/dto src/test/java/com/superpowers/test/auth/dto
git commit -m "feat(DEMO-1): add signup request/response DTOs with validation"
```

---

### Task 5: Duplicate-email exception and RFC 7807 exception handler

**Files:**
- Create: `src/main/java/com/superpowers/test/auth/exception/EmailAlreadyExistsException.java`
- Create: `src/main/java/com/superpowers/test/auth/exception/GlobalExceptionHandler.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `EmailAlreadyExistsException(String message)` — a `RuntimeException` thrown by Task 6's `SignupService` and mapped here to 409. `GlobalExceptionHandler` also maps `MethodArgumentNotValidException` (raised automatically by `@Valid` on Task 8's controller) to 400. No dedicated unit test — both paths are exercised end-to-end by Task 10's integration test (this is standard `@RestControllerAdvice` wiring with no independent logic worth unit-isolating).

- [ ] **Step 1: Write `EmailAlreadyExistsException.java`**

```java
package com.superpowers.test.auth.exception;

public class EmailAlreadyExistsException extends RuntimeException {

    public EmailAlreadyExistsException(String message) {
        super(message);
    }
}
```

- [ ] **Step 2: Write `GlobalExceptionHandler.java`**

```java
package com.superpowers.test.auth.exception;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed.");
        problem.setTitle("Validation Failed");
        problem.setProperty("errorCode", "VALIDATION_FAILED");
        problem.setProperty("message", "Request validation failed.");
        problem.setProperty("violations", violations);
        return problem;
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ProblemDetail handleEmailAlreadyExists(EmailAlreadyExistsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Email Already Exists");
        problem.setProperty("errorCode", "EMAIL_ALREADY_EXISTS");
        problem.setProperty("message", ex.getMessage());
        return problem;
    }

    public record FieldViolation(String field, String message) {
    }
}
```

- [ ] **Step 3: Verify the build still compiles**

Run: `./mvnw -q compile`
Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/superpowers/test/auth/exception
git commit -m "feat(DEMO-1): add duplicate-email exception and RFC7807 handler"
```

---

### Task 6: SignupService

**Files:**
- Create: `src/main/java/com/superpowers/test/auth/SignupService.java`
- Test: `src/test/java/com/superpowers/test/auth/SignupServiceTest.java`

**Interfaces:**
- Consumes: `UserRepository#findByEmailIgnoreCase(String): Optional<User>`, `UserRepository#save(User): User` (Task 3); `User(String email, String passwordHash, String name)` (Task 3); `PasswordEncoder#encode(CharSequence): String` (Spring Security, bean produced by Task 7); `EmailAlreadyExistsException` (Task 5).
- Produces: `SignupService#register(String name, String email, String rawPassword): String` (returns `usr_{id}`), consumed by Task 8's `SignupController`.

- [ ] **Step 1: Write the failing test**

```java
package com.superpowers.test.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.superpowers.test.auth.exception.EmailAlreadyExistsException;
import com.superpowers.test.user.User;
import com.superpowers.test.user.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class SignupServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private SignupService signupService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        signupService = new SignupService(userRepository, passwordEncoder);
    }

    @Test
    void hashesThePasswordBeforePersistingAndReturnsThePublicUserId() {
        when(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("SecurePassword123!")).thenReturn("hashed-value");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User toSave = invocation.getArgument(0);
            assertThat(toSave.getPasswordHash()).isEqualTo("hashed-value");
            ReflectionTestUtils.setField(toSave, "id", 10293L);
            return toSave;
        });

        String userId = signupService.register("Vivek Khamar", "user@example.com", "SecurePassword123!");

        assertThat(userId).isEqualTo("usr_10293");
        verify(passwordEncoder).encode("SecurePassword123!");
    }

    @Test
    void rejectsRegistrationWhenEmailAlreadyExistsWithoutAttemptingAWrite() {
        when(userRepository.findByEmailIgnoreCase("user@example.com"))
                .thenReturn(Optional.of(new User("user@example.com", "hash", "Existing User")));

        assertThatThrownBy(() -> signupService.register("Vivek Khamar", "user@example.com", "SecurePassword123!"))
                .isInstanceOf(EmailAlreadyExistsException.class)
                .hasMessage("An account with this email address already exists.");

        verifyNoMoreInteractions(passwordEncoder);
    }

    @Test
    void translatesADatabaseLevelDuplicateEmailRaceIntoTheDomainException() {
        when(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("SecurePassword123!")).thenReturn("hashed-value");
        when(userRepository.save(any(User.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> signupService.register("Vivek Khamar", "user@example.com", "SecurePassword123!"))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=SignupServiceTest`
Expected: FAIL — compilation error, `SignupService` not found

- [ ] **Step 3: Write `SignupService.java`**

```java
package com.superpowers.test.auth;

import com.superpowers.test.auth.exception.EmailAlreadyExistsException;
import com.superpowers.test.user.User;
import com.superpowers.test.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SignupService {

    private static final String DUPLICATE_EMAIL_MESSAGE = "An account with this email address already exists.";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public SignupService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public String register(String name, String email, String rawPassword) {
        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw new EmailAlreadyExistsException(DUPLICATE_EMAIL_MESSAGE);
        }

        User user = new User(email, passwordEncoder.encode(rawPassword), name);
        User saved;
        try {
            saved = userRepository.save(user);
        } catch (DataIntegrityViolationException ex) {
            throw new EmailAlreadyExistsException(DUPLICATE_EMAIL_MESSAGE);
        }

        return "usr_" + saved.getId();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=SignupServiceTest`
Expected: PASS (3 tests, 0 failures)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/superpowers/test/auth/SignupService.java src/test/java/com/superpowers/test/auth/SignupServiceTest.java
git commit -m "feat(DEMO-1): add SignupService with duplicate-email guard"
```

---

### Task 7: Security config (PasswordEncoder + stateless filter chain)

**Files:**
- Create: `src/main/java/com/superpowers/test/config/SecurityConfig.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `PasswordEncoder` bean (`BCryptPasswordEncoder`) consumed by Task 6's `SignupService`; `SecurityFilterChain` bean that permits `POST /api/v1/auth/signup` unauthenticated — required for Task 8/10's controller and integration tests to reach the endpoint at all (Spring Security denies everything by default once the security starter is on the classpath).

- [ ] **Step 1: Write `SecurityConfig.java`**

```java
package com.superpowers.test.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/signup").permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }
}
```

- [ ] **Step 2: Verify the build still compiles**

Run: `./mvnw -q compile`
Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/superpowers/test/config/SecurityConfig.java
git commit -m "feat(DEMO-1): add stateless security config for signup route"
```

---

### Task 8: SignupController

**Files:**
- Create: `src/main/java/com/superpowers/test/auth/SignupController.java`

**Interfaces:**
- Consumes: `SignupRequest` (Task 4), `SignupResponse.success(String): SignupResponse` (Task 4), `SignupService#register(String, String, String): String` (Task 6).
- Produces: `POST /api/v1/auth/signup` HTTP endpoint, exercised end-to-end by Task 10.

- [ ] **Step 1: Write `SignupController.java`**

```java
package com.superpowers.test.auth;

import com.superpowers.test.auth.dto.SignupRequest;
import com.superpowers.test.auth.dto.SignupResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class SignupController {

    private final SignupService signupService;

    public SignupController(SignupService signupService) {
        this.signupService = signupService;
    }

    @PostMapping("/signup")
    public ResponseEntity<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
        String userId = signupService.register(request.name(), request.email(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(SignupResponse.success(userId));
    }
}
```

- [ ] **Step 2: Verify the build still compiles**

Run: `./mvnw -q compile`
Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/superpowers/test/auth/SignupController.java
git commit -m "feat(DEMO-1): wire signup endpoint to SignupService"
```

---

### Task 9: Per-IP rate limiting

**Files:**
- Create: `src/main/java/com/superpowers/test/auth/ratelimit/RateLimitProperties.java`
- Create: `src/main/java/com/superpowers/test/auth/ratelimit/SignupRateLimitFilter.java`
- Test: `src/test/java/com/superpowers/test/auth/ratelimit/SignupRateLimitFilterTest.java`

**Interfaces:**
- Consumes: `app.rate-limit.signup.max-requests` / `.window-seconds` properties (Task 2).
- Produces: a servlet filter, auto-registered by Spring Boot's component scan, that returns HTTP 429 for the 11th+ `POST /api/v1/auth/signup` request from the same IP within the configured window; exercised end-to-end by Task 10.

- [ ] **Step 1: Write the failing test**

```java
package com.superpowers.test.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SignupRateLimitFilterTest {

    private static final String SIGNUP_PATH = "/api/v1/auth/signup";

    private RateLimitProperties properties;
    private SignupRateLimitFilter filter;
    private FilterChain passThroughChain;

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        properties.setMaxRequests(10);
        properties.setWindowSeconds(60);
        Clock clock = Clock.fixed(Instant.parse("2026-07-10T00:00:00Z"), ZoneOffset.UTC);
        filter = new SignupRateLimitFilter(properties, clock);
        passThroughChain = (req, res) -> ((MockHttpServletResponse) res).setStatus(200);
    }

    @Test
    void allowsUpToTheConfiguredLimitFromTheSameIp() throws Exception {
        for (int i = 0; i < 10; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(signupRequestFrom("10.0.0.1"), response, passThroughChain);

            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void throttlesTheEleventhRequestWithinTheWindow() throws Exception {
        for (int i = 0; i < 10; i++) {
            filter.doFilter(signupRequestFrom("10.0.0.2"), new MockHttpServletResponse(), passThroughChain);
        }

        MockHttpServletResponse eleventh = new MockHttpServletResponse();
        filter.doFilter(signupRequestFrom("10.0.0.2"), eleventh, passThroughChain);

        assertThat(eleventh.getStatus()).isEqualTo(429);
        assertThat(eleventh.getContentAsString()).contains("RATE_LIMIT_EXCEEDED");
    }

    @Test
    void tracksLimitsIndependentlyPerIp() throws Exception {
        for (int i = 0; i < 10; i++) {
            filter.doFilter(signupRequestFrom("10.0.0.3"), new MockHttpServletResponse(), passThroughChain);
        }

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(signupRequestFrom("10.0.0.4"), response, passThroughChain);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void ignoresRequestsToOtherPaths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, passThroughChain);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private MockHttpServletRequest signupRequestFrom(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", SIGNUP_PATH);
        request.setRemoteAddr(ip);
        return request;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=SignupRateLimitFilterTest`
Expected: FAIL — compilation error, `RateLimitProperties`/`SignupRateLimitFilter` not found

- [ ] **Step 3: Write `RateLimitProperties.java`**

```java
package com.superpowers.test.auth.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.rate-limit.signup")
public class RateLimitProperties {

    private int maxRequests = 10;
    private int windowSeconds = 60;

    public int getMaxRequests() {
        return maxRequests;
    }

    public void setMaxRequests(int maxRequests) {
        this.maxRequests = maxRequests;
    }

    public int getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(int windowSeconds) {
        this.windowSeconds = windowSeconds;
    }
}
```

- [ ] **Step 4: Write `SignupRateLimitFilter.java`**

```java
package com.superpowers.test.auth.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class SignupRateLimitFilter extends OncePerRequestFilter implements Ordered {

    private static final String SIGNUP_PATH = "/api/v1/auth/signup";

    private final RateLimitProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public SignupRateLimitFilter(RateLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    SignupRateLimitFilter(RateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!SIGNUP_PATH.equals(request.getRequestURI()) || !"POST".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(request);
        long nowMillis = clock.millis();
        long windowMillis = properties.getWindowSeconds() * 1000L;
        Window window = windows.computeIfAbsent(clientIp, ip -> new Window(nowMillis));

        if (window.incrementAndCheckExceeded(nowMillis, windowMillis, properties.getMaxRequests())) {
            writeTooManyRequests(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void writeTooManyRequests(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_TOO_MANY_REQUESTS);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(
                "{\"errorCode\":\"RATE_LIMIT_EXCEEDED\",\"message\":\"Too many signup attempts. "
                        + "Please try again later.\"}");
    }

    private static final class Window {
        private long windowStartMillis;
        private int count;

        private Window(long windowStartMillis) {
            this.windowStartMillis = windowStartMillis;
            this.count = 0;
        }

        private synchronized boolean incrementAndCheckExceeded(long nowMillis, long windowMillis, int maxRequests) {
            if (nowMillis - windowStartMillis >= windowMillis) {
                windowStartMillis = nowMillis;
                count = 1;
                return false;
            }
            count++;
            return count > maxRequests;
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=SignupRateLimitFilterTest`
Expected: PASS (4 tests, 0 failures)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/superpowers/test/auth/ratelimit src/test/java/com/superpowers/test/auth/ratelimit
git commit -m "feat(DEMO-1): add per-IP rate limiting for signup endpoint"
```

---

### Task 10: End-to-end integration coverage

**Files:**
- Test: `src/test/java/com/superpowers/test/auth/SignupControllerIntegrationTest.java`

**Interfaces:**
- Consumes: the full stack built in Tasks 1-9 (`SignupController`, `SignupService`, `UserRepository`, `SecurityConfig`, `GlobalExceptionHandler`, `SignupRateLimitFilter`) via a real HTTP client against a real Postgres (Testcontainers).
- Produces: nothing consumed by later tasks — this is the final verification task.

- [ ] **Step 1: Write the test**

```java
package com.superpowers.test.auth;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class SignupControllerIntegrationTest {

    private static final String SIGNUP_PATH = "/api/v1/auth/signup";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    private HttpEntity<String> jsonBody(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    // AC1-5
    @Test
    void returns201AndHashesThePasswordOnValidRegistration() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                SIGNUP_PATH,
                jsonBody("{\"name\":\"Vivek Khamar\",\"email\":\"user@example.com\","
                        + "\"password\":\"SecurePassword123!\"}"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody())
                .contains("\"status\":\"success\"")
                .contains("\"message\":\"User registered successfully.\"")
                .contains("\"userId\":\"usr_");

        User saved = userRepository.findByEmailIgnoreCase("user@example.com").orElseThrow();
        assertThat(saved.getPasswordHash()).isNotEqualTo("SecurePassword123!");
        assertThat(passwordEncoder.matches("SecurePassword123!", saved.getPasswordHash())).isTrue();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    // AC6-9
    @Test
    void returns409WithStructuredErrorOnDuplicateEmail() {
        userRepository.save(new User("user@example.com", passwordEncoder.encode("Existing123!"), "Existing User"));

        ResponseEntity<String> response = restTemplate.postForEntity(
                SIGNUP_PATH,
                jsonBody("{\"name\":\"Vivek Khamar\",\"email\":\"user@example.com\","
                        + "\"password\":\"SecurePassword123!\"}"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody())
                .contains("\"errorCode\":\"EMAIL_ALREADY_EXISTS\"")
                .contains("An account with this email address already exists.");
    }

    // AC11-13
    @Test
    void returns400WhenRequiredFieldsAreMissing() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                SIGNUP_PATH,
                jsonBody("{\"email\":\"user@example.com\",\"password\":\"SecurePassword123!\"}"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    // AC14-15
    @Test
    void returns400ListingViolationsForMalformedEmailAndWeakPassword() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                SIGNUP_PATH,
                jsonBody("{\"name\":\"Vivek Khamar\",\"email\":\"not-an-email\",\"password\":\"weak\"}"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .contains("\"errorCode\":\"VALIDATION_FAILED\"")
                .contains("\"violations\"")
                .contains("\"field\":\"email\"")
                .contains("\"field\":\"password\"");
    }

    // AC16-19
    @Test
    void returns429AfterMoreThanTenSignupAttemptsFromTheSameIpWithinAMinute() {
        for (int i = 0; i < 10; i++) {
            restTemplate.postForEntity(
                    SIGNUP_PATH,
                    jsonBody("{\"name\":\"Vivek Khamar\",\"email\":\"user" + i + "@example.com\","
                            + "\"password\":\"SecurePassword123!\"}"),
                    String.class);
        }

        ResponseEntity<String> response = restTemplate.postForEntity(
                SIGNUP_PATH,
                jsonBody("{\"name\":\"Vivek Khamar\",\"email\":\"user-overflow@example.com\","
                        + "\"password\":\"SecurePassword123!\"}"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).contains("RATE_LIMIT_EXCEEDED");
    }
}
```

- [ ] **Step 2: Run the full test suite**

Run: `./mvnw -q test`
Expected: PASS — all unit + integration tests green, including the 5 new integration tests above

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/superpowers/test/auth/SignupControllerIntegrationTest.java
git commit -m "test(DEMO-1): add end-to-end integration coverage for signup endpoint"
```

---

## Self-Review Notes

- **Spec coverage:** AC1-5 → Task 10's `returns201...`; AC6-10 → Tasks 5/6/10; AC11-13 → Task 4/10 (`@NotBlank`); AC14-15 → Task 4/10 (`@Pattern` + `violations` list); AC16-19 → Task 9/10. All 4 scenarios have a task and an integration-test assertion.
- **Type consistency checked:** `SignupService.register(String, String, String): String` signature matches its Task 6 definition and Task 8's controller call (`request.name(), request.email(), request.password()`); `User` constructor `(email, passwordHash, name)` used identically in Tasks 3, 6, 10; `RateLimitProperties`/`SignupRateLimitFilter` constructor shapes match between Task 9's production code and its test.
- **No placeholders** — every step has complete, runnable code.
