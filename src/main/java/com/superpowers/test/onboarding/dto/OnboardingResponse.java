package com.superpowers.test.onboarding.dto;

public record OnboardingResponse(String status, String message) {

    public static OnboardingResponse success() {
        return new OnboardingResponse("success", "User onboarding profile completed successfully.");
    }
}
