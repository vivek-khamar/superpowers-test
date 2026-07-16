package com.superpowers.test.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.superpowers.test.admin.dto.AdminUserPageResponse;
import com.superpowers.test.user.User;
import com.superpowers.test.user.UserRepository;
import com.superpowers.test.user.UserStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

class AdminUserServiceTest {

    private UserRepository userRepository;
    private AdminUserService adminUserService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        adminUserService = new AdminUserService(userRepository);
    }

    private User userWith(Long id, String name, String email, String role, UserStatus status) {
        User user = new User(email, "hash", name, role);
        ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(user, "status", status);
        ReflectionTestUtils.setField(user, "createdAt", Instant.parse("2026-03-15T10:00:00Z"));
        return user;
    }

    // AC1-5: both role types come back unified in one response envelope
    @Test
    void mapsUsersOfBothRolesIntoTheResponseEnvelope() {
        User standardUser = userWith(1L, "Jane Doe", "jane.doe@example.com", "USER", UserStatus.REGISTERED);
        User adminUser = userWith(2L, "Vivek Khamar", "vivekhamar@gmail.com", "ADMIN", UserStatus.REGISTERED);
        Page<User> page = new PageImpl<>(List.of(standardUser, adminUser), PageRequest.of(0, 20), 2);
        when(userRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        AdminUserPageResponse response = adminUserService.listUsers(0, 20, null, null);

        assertThat(response.content()).hasSize(2);
        assertThat(response.content().get(0).id()).isEqualTo("1");
        assertThat(response.content().get(0).role()).isEqualTo("ROLE_USER");
        assertThat(response.content().get(1).role()).isEqualTo("ROLE_ADMIN");
        assertThat(response.content().get(1).createdAt()).isEqualTo(Instant.parse("2026-03-15T10:00:00Z"));
        assertThat(response.pageable().pageNumber()).isZero();
        assertThat(response.pageable().pageSize()).isEqualTo(20);
        assertThat(response.pageable().totalElements()).isEqualTo(2);
        assertThat(response.pageable().totalPages()).isEqualTo(1);
    }

    // Decision 8: default sort must be createdAt descending
    @Test
    void requestsPageableSortedByCreatedAtDescending() {
        Page<User> emptyPage = new PageImpl<>(List.of(), PageRequest.of(1, 5), 0);
        when(userRepository.findAll(any(Specification.class), eq(PageRequest.of(1, 5, Sort.by("createdAt").descending()))))
                .thenReturn(emptyPage);

        AdminUserPageResponse response = adminUserService.listUsers(1, 5, null, null);

        assertThat(response.content()).isEmpty();
        assertThat(response.pageable().pageNumber()).isEqualTo(1);
        assertThat(response.pageable().pageSize()).isEqualTo(5);
    }
}
