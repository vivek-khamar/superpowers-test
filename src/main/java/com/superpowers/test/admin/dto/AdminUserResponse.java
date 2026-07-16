package com.superpowers.test.admin.dto;

import java.time.Instant;

public record AdminUserResponse(String id, String name, String email, String role, String status, Instant createdAt) {
}
