package com.superpowers.test.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final AppJwtProperties properties;

    public JwtService(AppJwtProperties properties) {
        this.properties = properties;
    }

    public String generateToken(String subject, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(subject)
                .claim("roles", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(properties.getExpirationSeconds())))
                .signWith(signingKey())
                .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getExpirationSeconds() {
        return properties.getExpirationSeconds();
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
    }
}
