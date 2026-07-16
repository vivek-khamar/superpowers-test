package com.superpowers.test.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(classes = S3FileStorageServiceIntegrationTest.TestConfig.class)
class S3FileStorageServiceIntegrationTest {

    private static final String BUCKET_NAME = "onboarding-test-bucket";

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
            .withServices("s3");

    @EnableConfigurationProperties(AwsS3Properties.class)
    @Import({S3ClientConfig.class, S3FileStorageService.class})
    static class TestConfig {
    }

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
        registry.add("app.aws.s3.endpoint-override", () -> localstack.getEndpoint().toString());
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
                    public InputStream getInputStream() throws IOException {
                        throw new IOException("simulated read failure");
                    }
                };

        assertThatThrownBy(() -> fileStorageService.upload("resumes/1/broken.pdf", brokenFile))
                .isInstanceOf(FileStorageException.class);
    }
}
