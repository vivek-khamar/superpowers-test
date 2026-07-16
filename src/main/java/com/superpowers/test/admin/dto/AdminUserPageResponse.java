package com.superpowers.test.admin.dto;

import java.util.List;

public record AdminUserPageResponse(List<AdminUserResponse> content, PageableInfo pageable) {
}
