package com.superpowers.test.onboarding.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;

public record OnboardingRequest(

        @NotBlank(message = "name is required")
        String name,

        @NotBlank(message = "jobPreference is required")
        @Pattern(regexp = "FULL_TIME|PART_TIME", message = "jobPreference must be one of FULL_TIME or PART_TIME")
        String jobPreference,

        @NotNull(message = "preferredJobFunctions is required")
        List<String> preferredJobFunctions,

        @NotNull(message = "preferredLocations is required")
        List<String> preferredLocations) {
}
