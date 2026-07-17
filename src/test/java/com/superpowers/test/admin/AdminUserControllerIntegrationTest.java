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
