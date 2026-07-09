package com.superpowers.test.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DataJpaTest
class UserRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private UserRepository userRepository;

    @Test
    void findsUserByEmailCaseInsensitively() {
        userRepository.save(new User("user@example.com", "hash", "Alex Doe", "USER"));

        assertThat(userRepository.findByEmailIgnoreCase("USER@EXAMPLE.COM")).isPresent();
        assertThat(userRepository.findByEmailIgnoreCase("nobody@example.com")).isEmpty();
    }

    @Test
    void defaultsFailedAttemptsToZeroAndLockedUntilToNull() {
        User saved = userRepository.save(new User("fresh@example.com", "hash", "Fresh User", "USER"));

        assertThat(saved.getFailedAttempts()).isZero();
        assertThat(saved.getLockedUntil()).isNull();
    }
}
