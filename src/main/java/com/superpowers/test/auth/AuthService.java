package com.superpowers.test.auth;

import com.superpowers.test.auth.dto.LoginResponse;
import com.superpowers.test.auth.dto.UserView;
import com.superpowers.test.auth.exception.AccountLockedException;
import com.superpowers.test.auth.exception.AuthenticationFailedException;
import com.superpowers.test.user.User;
import com.superpowers.test.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

    // A real BCrypt hash of an unused fixed value, compared against on every
    // "unknown email" path so a missing account costs the same one BCrypt
    // round-trip as a wrong password on a real account (timing-attack defense).
    private static final String DUMMY_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResult login(String email, String rawPassword) {
        Optional<User> maybeUser = userRepository.findByEmailIgnoreCase(email);

        if (maybeUser.isPresent()) {
            User existing = maybeUser.get();
            if (existing.getLockedUntil() != null && existing.getLockedUntil().isAfter(Instant.now())) {
                log.warn("Login attempt for locked account: {}", maskEmail(email));
                throw new AccountLockedException(existing.getLockedUntil());
            }
        }

        String hashToCheck = maybeUser.map(User::getPasswordHash).orElse(DUMMY_HASH);
        boolean matches = passwordEncoder.matches(rawPassword, hashToCheck);

        if (matches && maybeUser.isPresent()) {
            return issueToken(maybeUser.get());
        }

        maybeUser.ifPresent(this::recordFailedAttempt);
        throw new AuthenticationFailedException();
    }

    private AuthResult issueToken(User user) {
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        String token = jwtService.generateToken(String.valueOf(user.getId()), user.getRole());
        UserView view = new UserView(String.valueOf(user.getId()), user.getEmail(), user.getName());
        return new AuthResult(token, jwtService.getExpirationSeconds(), LoginResponse.success(view));
    }

    private void recordFailedAttempt(User user) {
        int attempts = user.getFailedAttempts() + 1;
        user.setFailedAttempts(attempts);
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            user.setLockedUntil(Instant.now().plus(LOCKOUT_DURATION));
            log.warn("Account locked after {} failed attempts: {}", attempts, maskEmail(user.getEmail()));
        }
        userRepository.save(user);
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + email.substring(at);
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
