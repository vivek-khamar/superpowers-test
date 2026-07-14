package com.superpowers.test.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.superpowers.test.auth.exception.AccountLockedException;
import com.superpowers.test.auth.exception.AuthenticationFailedException;
import com.superpowers.test.user.User;
import com.superpowers.test.user.UserRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String EMAIL = "user@example.com";
    private static final String RAW_PASSWORD = "RawPasswordText123!";

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtService jwtService;

    private PasswordEncoder passwordEncoder;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        authService = new AuthService(userRepository, passwordEncoder, jwtService);
    }

    private User activeUser() {
        return new User(EMAIL, passwordEncoder.encode(RAW_PASSWORD), "Alex Doe", "USER");
    }

    // AC1-5: valid credentials issue a token and reset any prior failure count.
    @Test
    void returnsTokenAndUserViewOnValidCredentials() {
        User user = activeUser();
        user.setFailedAttempts(2);
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
        when(jwtService.generateToken(any(), any())).thenReturn("signed-jwt");
        when(jwtService.getExpirationSeconds()).thenReturn(86400L);

        AuthResult result = authService.login(EMAIL, RAW_PASSWORD);

        assertThat(result.token()).isEqualTo("signed-jwt");
        assertThat(result.expirationSeconds()).isEqualTo(86400L);
        assertThat(result.body().status()).isEqualTo("success");
        assertThat(result.body().user().email()).isEqualTo(EMAIL);
        assertThat(result.body().user().name()).isEqualTo("Alex Doe");
        assertThat(user.getFailedAttempts()).isZero();
        assertThat(user.getLockedUntil()).isNull();
    }

    // AC6-9: wrong password -> generic 401-mapped exception, no detail leaked.
    @Test
    void rejectsWrongPasswordWithGenericAuthFailure() {
        User user = activeUser();
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(EMAIL, "wrong-password"))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    // AC6-9: unknown email maps to the exact same exception as a wrong password.
    @Test
    void rejectsUnknownEmailWithSameGenericAuthFailure() {
        when(userRepository.findByEmailIgnoreCase("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login("ghost@example.com", RAW_PASSWORD))
                .isInstanceOf(AuthenticationFailedException.class);

        verify(userRepository, never()).save(any());
    }

    // AC16-18: 5th consecutive failure sets the lockout window.
    @Test
    void locksAccountAfterFifthConsecutiveFailure() {
        User user = activeUser();
        user.setFailedAttempts(4);
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(EMAIL, "wrong-password"))
                .isInstanceOf(AuthenticationFailedException.class);

        assertThat(user.getFailedAttempts()).isEqualTo(5);
        assertThat(user.getLockedUntil()).isAfter(Instant.now());
    }

    // AC19: further attempts against an already-locked account get 423, not 401.
    @Test
    void rejectsLoginForAlreadyLockedAccount() {
        User user = activeUser();
        user.setLockedUntil(Instant.now().plusSeconds(600));
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(EMAIL, RAW_PASSWORD))
                .isInstanceOf(AccountLockedException.class);
    }

    // AC10: unknown-email and wrong-password paths should cost about the same
    // wall-clock time (both run one BCrypt comparison), so guessing valid emails
    // by timing isn't meaningfully easier than guessing passwords.
    @Test
    void takesRoughlyEqualTimeForUnknownEmailAndWrongPassword() {
        User user = activeUser();
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
        when(userRepository.findByEmailIgnoreCase("ghost@example.com")).thenReturn(Optional.empty());

        long start1 = System.nanoTime();
        assertThatThrownBy(() -> authService.login(EMAIL, "wrong-password"))
                .isInstanceOf(AuthenticationFailedException.class);
        long knownElapsed = System.nanoTime() - start1;

        long start2 = System.nanoTime();
        assertThatThrownBy(() -> authService.login("ghost@example.com", RAW_PASSWORD))
                .isInstanceOf(AuthenticationFailedException.class);
        long unknownElapsed = System.nanoTime() - start2;

        double ratio = (double) Math.max(knownElapsed, unknownElapsed) / Math.min(knownElapsed, unknownElapsed);
        assertThat(ratio).isLessThan(3.0);
    }

    // AC20: a failed attempt logs a masked identity and never the raw password.
    @Test
    void logsMaskedIdentityOnFailedAttemptWithoutRawPassword() {
        Logger authServiceLogger = (Logger) LoggerFactory.getLogger(AuthService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        authServiceLogger.addAppender(appender);

        try {
            User user = activeUser();
            user.setFailedAttempts(4);
            when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> authService.login(EMAIL, "wrong-password"))
                    .isInstanceOf(AuthenticationFailedException.class);

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> {
                        assertThat(message).contains("u***@example.com");
                        assertThat(message).doesNotContain("wrong-password");
                    });
        } finally {
            authServiceLogger.detachAppender(appender);
        }
    }
}
