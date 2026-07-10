package com.superpowers.test.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SignupRequestValidationTest {

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

    @Test
    void acceptsAWellFormedRequest() {
        SignupRequest request = new SignupRequest("Vivek Khamar", "user@example.com", "SecurePassword123!");

        Set<ConstraintViolation<SignupRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void rejectsBlankName(String blankName) {
        SignupRequest request = new SignupRequest(blankName, "user@example.com", "SecurePassword123!");

        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    void rejectsNullRequiredFields() {
        SignupRequest request = new SignupRequest(null, null, null);

        assertThat(validator.validate(request)).hasSize(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-email", "missing-at-sign.com", "user@", "@example.com"})
    void rejectsMalformedEmail(String badEmail) {
        SignupRequest request = new SignupRequest("Vivek Khamar", badEmail, "SecurePassword123!");

        assertThat(validator.validate(request)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "short1!",          // < 8 chars
            "alllowercase123!", // no uppercase
            "NoDigitsHere!",    // no digit
            "NoSpecial123"      // no special char
    })
    void rejectsPasswordsFailingComplexityRules(String weakPassword) {
        SignupRequest request = new SignupRequest("Vivek Khamar", "user@example.com", weakPassword);

        assertThat(validator.validate(request)).isNotEmpty();
    }
}
