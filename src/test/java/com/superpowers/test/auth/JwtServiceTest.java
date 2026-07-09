package com.superpowers.test.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        AppJwtProperties properties = new AppJwtProperties();
        properties.setSecret("test-secret-key-that-is-at-least-32-bytes-long!!");
        properties.setExpirationSeconds(86400);
        jwtService = new JwtService(properties);
    }

    @Test
    void generatesTokenWithSubjectRoleAndExpiryClaims() {
        String token = jwtService.generateToken("42", "USER");

        Claims claims = jwtService.parseClaims(token);

        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get("roles", String.class)).isEqualTo("USER");
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
    }

    @Test
    void exposesConfiguredExpirationSeconds() {
        assertThat(jwtService.getExpirationSeconds()).isEqualTo(86400);
    }
}
