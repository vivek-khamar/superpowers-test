package com.superpowers.test.auth;

import com.superpowers.test.auth.exception.EmailAlreadyExistsException;
import com.superpowers.test.user.User;
import com.superpowers.test.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SignupService {

    private static final Logger log = LoggerFactory.getLogger(SignupService.class);
    private static final String DUPLICATE_EMAIL_MESSAGE = "An account with this email address already exists.";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public SignupService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public String register(String name, String email, String rawPassword) {
        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            log.info("Signup rejected: email already registered");
            throw new EmailAlreadyExistsException(DUPLICATE_EMAIL_MESSAGE);
        }

        User user = new User(email, passwordEncoder.encode(rawPassword), name);
        User saved;
        try {
            saved = userRepository.save(user);
        } catch (DataIntegrityViolationException ex) {
            log.info("Signup rejected: duplicate email detected at database level");
            throw new EmailAlreadyExistsException(DUPLICATE_EMAIL_MESSAGE);
        }

        log.info("User registered successfully with id {}", saved.getId());
        return "usr_" + saved.getId();
    }
}
