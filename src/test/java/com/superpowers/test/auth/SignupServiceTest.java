package com.superpowers.test.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.superpowers.test.auth.exception.EmailAlreadyExistsException;
import com.superpowers.test.user.User;
import com.superpowers.test.user.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class SignupServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private SignupService signupService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        signupService = new SignupService(userRepository, passwordEncoder);
    }

    @Test
    void hashesThePasswordBeforePersistingAndReturnsThePublicUserId() {
        when(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("SecurePassword123!")).thenReturn("hashed-value");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User toSave = invocation.getArgument(0);
            assertThat(toSave.getPasswordHash()).isEqualTo("hashed-value");
            ReflectionTestUtils.setField(toSave, "id", 10293L);
            return toSave;
        });

        String userId = signupService.register("Vivek Khamar", "user@example.com", "SecurePassword123!");

        assertThat(userId).isEqualTo("usr_10293");
        verify(passwordEncoder).encode("SecurePassword123!");
    }

    @Test
    void rejectsRegistrationWhenEmailAlreadyExistsWithoutAttemptingAWrite() {
        when(userRepository.findByEmailIgnoreCase("user@example.com"))
                .thenReturn(Optional.of(new User("user@example.com", "hash", "Existing User")));

        assertThatThrownBy(() -> signupService.register("Vivek Khamar", "user@example.com", "SecurePassword123!"))
                .isInstanceOf(EmailAlreadyExistsException.class)
                .hasMessage("An account with this email address already exists.");

        verifyNoMoreInteractions(passwordEncoder);
    }

    @Test
    void translatesADatabaseLevelDuplicateEmailRaceIntoTheDomainException() {
        when(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("SecurePassword123!")).thenReturn("hashed-value");
        when(userRepository.save(any(User.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> signupService.register("Vivek Khamar", "user@example.com", "SecurePassword123!"))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }
}
