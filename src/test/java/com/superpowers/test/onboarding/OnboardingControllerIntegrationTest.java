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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
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
            .withServices("s3");

    @BeforeAll
    static void createBucket() throws Exception {
        localstack.execInContainer("awslocal", "s3", "mb", "s3://" + BUCKET_NAME);
    }

    @DynamicPropertySource
    static void awsProperties(DynamicPropertyRegistry registry) {
        registry.add("app.aws.s3.bucket-name", () -> BUCKET_NAME);
        registry.add("app.aws.s3.region", () -> localstack.getRegion());
        registry.add("app.aws.s3.endpoint-override", () -> localstack.getEndpoint().toString());
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
