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

class LoginRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void acceptsWellFormedRequest() {
        LoginRequest request = new LoginRequest("user@example.com", "RawPasswordText123!");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsBlankEmail() {
        Set<ConstraintViolation<LoginRequest>> violations =
                validator.validate(new LoginRequest("", "RawPasswordText123!"));

        assertThat(violations).isNotEmpty();
    }

    @Test
    void rejectsBlankPassword() {
        Set<ConstraintViolation<LoginRequest>> violations =
                validator.validate(new LoginRequest("user@example.com", ""));

        assertThat(violations).isNotEmpty();
    }

    @Test
    void rejectsStructurallyInvalidEmail() {
        Set<ConstraintViolation<LoginRequest>> violations =
                validator.validate(new LoginRequest("not-an-email", "RawPasswordText123!"));

        assertThat(violations).isNotEmpty();
    }
}
