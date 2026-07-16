package com.superpowers.test.onboarding.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OnboardingRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    private OnboardingRequest validRequest(String jobPreference) {
        return new OnboardingRequest(
                "Vivek Khamar", jobPreference, List.of("Backend Engineer"), List.of("Mumbai"));
    }

    @Test
    void acceptsAWellFormedRequest() {
        Set<ConstraintViolation<OnboardingRequest>> violations = validator.validate(validRequest("FULL_TIME"));

        assertThat(violations).isEmpty();
    }

    @Test
    void acceptsPartTimeAsWellAsFullTime() {
        assertThat(validator.validate(validRequest("PART_TIME"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"full_time", "CONTRACT", "FULLTIME", ""})
    void rejectsJobPreferenceValuesOtherThanTheTwoAllowed(String badValue) {
        assertThat(validator.validate(validRequest(badValue))).isNotEmpty();
    }

    @Test
    void rejectsBlankName() {
        OnboardingRequest request = new OnboardingRequest("", "FULL_TIME", List.of("Backend Engineer"), List.of("Mumbai"));

        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    void rejectsNullLists() {
        OnboardingRequest request = new OnboardingRequest("Vivek Khamar", "FULL_TIME", null, null);

        assertThat(validator.validate(request)).hasSize(2);
    }
}
