package com.superpowers.test.auth;

import com.superpowers.test.auth.dto.LoginResponse;

public record AuthResult(String token, long expirationSeconds, LoginResponse body) {
}
