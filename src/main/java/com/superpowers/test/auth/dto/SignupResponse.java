package com.superpowers.test.auth.dto;

public record SignupResponse(String status, String message, String userId) {

    public static SignupResponse success(String userId) {
        return new SignupResponse("success", "User registered successfully.", userId);
    }
}
