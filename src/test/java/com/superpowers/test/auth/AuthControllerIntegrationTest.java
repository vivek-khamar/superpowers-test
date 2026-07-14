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
class AuthControllerIntegrationTest {

    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String EMAIL = "user@example.com";
    private static final String RAW_PASSWORD = "RawPasswordText123!";

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
        userRepository.save(new User(EMAIL, passwordEncoder.encode(RAW_PASSWORD), "Alex Doe", "USER"));
    }

    private HttpEntity<String> jsonBody(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    // AC1-5
    @Test
    void returns200WithSecureHttpOnlyCookieOnValidCredentials() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                LOGIN_PATH,
                jsonBody("{\"email\":\"" + EMAIL + "\",\"password\":\"" + RAW_PASSWORD + "\"}"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("\"status\":\"success\"")
                .contains("Alex Doe")
                .contains(EMAIL);

        String cookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(cookie).isNotNull();
        assertThat(cookie).contains("jwt=").contains("HttpOnly").contains("Secure").contains("SameSite=Strict");
    }

    // AC6-9
    @Test
    void returns401WithGenericProblemBodyOnWrongPassword() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                LOGIN_PATH,
                jsonBody("{\"email\":\"" + EMAIL + "\",\"password\":\"wrong-password\"}"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody())
                .contains("\"errorCode\":\"AUTH_FAILED\"")
                .contains("Invalid email or password.");
    }

    // AC6-9
    @Test
    void returns401WithSameGenericBodyForUnknownEmail() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                LOGIN_PATH,
                jsonBody("{\"email\":\"ghost@example.com\",\"password\":\"" + RAW_PASSWORD + "\"}"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("\"errorCode\":\"AUTH_FAILED\"");
    }

    // AC11-13
    @Test
    void returns400WhenPasswordIsBlank() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                LOGIN_PATH,
                jsonBody("{\"email\":\"" + EMAIL + "\",\"password\":\"\"}"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    // AC11-13
    @Test
    void returns400WhenEmailIsMissing() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                LOGIN_PATH,
                jsonBody("{\"password\":\"" + RAW_PASSWORD + "\"}"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // AC14-15
    @Test
    void returns400WhenEmailFailsStructuralRegexCheck() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                LOGIN_PATH,
                jsonBody("{\"email\":\"not-an-email\",\"password\":\"" + RAW_PASSWORD + "\"}"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // AC16-19
    @Test
    void locksAccountAfter5FailuresAndReturns423OnNextAttempt() {
        for (int i = 0; i < 5; i++) {
            restTemplate.postForEntity(
                    LOGIN_PATH,
                    jsonBody("{\"email\":\"" + EMAIL + "\",\"password\":\"wrong-password\"}"),
                    String.class);
        }

        ResponseEntity<String> response = restTemplate.postForEntity(
                LOGIN_PATH,
                jsonBody("{\"email\":\"" + EMAIL + "\",\"password\":\"" + RAW_PASSWORD + "\"}"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(response.getBody()).contains("\"errorCode\":\"ACCOUNT_LOCKED\"");
    }
}
