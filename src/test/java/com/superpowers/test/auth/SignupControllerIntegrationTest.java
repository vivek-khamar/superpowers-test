package com.superpowers.test.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.superpowers.test.user.User;
import com.superpowers.test.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

// Tests share one Spring context (and one in-memory SignupRateLimitFilter) across this class,
// and TestRestTemplate always calls from the same loopback IP. The rate-limit-exhausting test
// is therefore pinned to run last via @TestMethodOrder so it never starves the other tests' IP budget.
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SignupControllerIntegrationTest {

    private static final String SIGNUP_PATH = "/api/v1/auth/signup";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

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
    @Order(1)
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
    @Order(2)
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
    @Order(3)
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
    @Order(4)
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
    @Order(5)
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
