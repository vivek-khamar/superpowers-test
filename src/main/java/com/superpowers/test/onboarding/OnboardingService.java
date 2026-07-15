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
