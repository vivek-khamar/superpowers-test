package com.superpowers.test.auth.dto;

public record LoginResponse(String status, String message, UserView user) {

    public static LoginResponse success(UserView user) {
        return new LoginResponse("success", "Authentication successful", user);
    }
}
