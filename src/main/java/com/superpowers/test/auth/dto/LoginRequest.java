package com.superpowers.test.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record LoginRequest(

        @NotBlank(message = "email is required")
        @Email(message = "email must be a well-formed address")
        @Pattern(
                regexp = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$",
                message = "email must be a well-formed address")
        String email,

        @NotBlank(message = "password is required")
        String password) {
}
