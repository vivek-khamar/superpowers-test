package com.superpowers.test.onboarding;

import com.superpowers.test.onboarding.dto.OnboardingRequest;
import com.superpowers.test.onboarding.dto.OnboardingResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/user")
public class OnboardingController {

    private final OnboardingService onboardingService;

    public OnboardingController(OnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    @PutMapping(value = "/onboarding", consumes = "multipart/form-data")
    public ResponseEntity<OnboardingResponse> completeOnboarding(
            Authentication authentication,
            @RequestPart("profileData") @Valid OnboardingRequest profileData,
            @RequestPart("profilePicture") MultipartFile profilePicture,
            @RequestPart("resume") MultipartFile resume) {
        Long userId = Long.valueOf(authentication.getName());
        OnboardingResponse response = onboardingService.completeOnboarding(userId, profileData, profilePicture, resume);
        return ResponseEntity.ok(response);
    }
}
