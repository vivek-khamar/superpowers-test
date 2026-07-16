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
