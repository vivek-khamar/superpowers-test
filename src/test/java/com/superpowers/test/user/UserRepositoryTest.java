package com.superpowers.test.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@DataJpaTest
class UserRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    @Autowired
    private UserRepository userRepository;

    @Test
    void findsUserByEmailCaseInsensitively() {
        userRepository.save(new User("user@example.com", "hash", "Alex Doe"));

        assertThat(userRepository.findByEmailIgnoreCase("USER@EXAMPLE.COM")).isPresent();
        assertThat(userRepository.findByEmailIgnoreCase("nobody@example.com")).isEmpty();
    }

    @Test
    void populatesGeneratedIdAndTimestampsOnSave() {
        User saved = userRepository.save(new User("fresh@example.com", "hash", "Fresh User"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void rejectsDuplicateEmailAtTheDatabaseLevelRegardlessOfCase() {
        userRepository.saveAndFlush(new User("dup@example.com", "hash", "First"));

        User duplicate = new User("DUP@example.com", "hash", "Second");
        assertThatThrownBy(() -> userRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void defaultsFailedAttemptsToZeroAndLockedUntilToNull() {
        User saved = userRepository.save(new User("locked-default@example.com", "hash", "Fresh User", "USER"));

        assertThat(saved.getFailedAttempts()).isZero();
        assertThat(saved.getLockedUntil()).isNull();
    }
}
