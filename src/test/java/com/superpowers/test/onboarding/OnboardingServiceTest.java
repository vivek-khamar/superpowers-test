package com.superpowers.test.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
        when(fileStorageService.upload(any(), any())).thenReturn("returned-key");

        OnboardingResponse response =
                onboardingService.completeOnboarding(USER_ID, validRequest(), picture(), resume("content".getBytes()));

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

        assertThatThrownBy(() ->
                        onboardingService.completeOnboarding(USER_ID, validRequest(), badPicture, resume("content".getBytes())))
                .isInstanceOf(InvalidFileException.class);

        verify(fileStorageService, never()).upload(any(), any());
        verify(userRepository, never()).save(any());
    }

    // AC12-16: S3 failure means no DB write occurs
    @Test
    void propagatesStorageFailureWithoutPersistingAnything() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existingUser()));
        when(fileStorageService.upload(any(), any())).thenThrow(new FileStorageException("boom", new RuntimeException()));

        assertThatThrownBy(() ->
                        onboardingService.completeOnboarding(USER_ID, validRequest(), picture(), resume("content".getBytes())))
                .isInstanceOf(FileStorageException.class);

        verify(userRepository, never()).save(any());
    }
}
