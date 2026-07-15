package com.superpowers.test.auth;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthenticationFilterTest {

    private JwtService jwtService;
    private JwtAuthenticationFilter filter;
    private FilterChain passThroughChain;

    @BeforeEach
    void setUp() {
        AppJwtProperties properties = new AppJwtProperties();
        properties.setSecret("test-secret-key-that-is-at-least-32-bytes-long!!");
        properties.setExpirationSeconds(86400);
        jwtService = new JwtService(properties);
        filter = new JwtAuthenticationFilter(jwtService);
        passThroughChain = (req, res) -> ((MockHttpServletResponse) res).setStatus(200);
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatesFromBearerHeader() throws Exception {
        String token = jwtService.generateToken("42", "USER");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);

        filter.doFilter(request, new MockHttpServletResponse(), passThroughChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("42");
    }

    @Test
    void authenticatesFromJwtCookieWhenNoHeaderPresent() throws Exception {
        String token = jwtService.generateToken("99", "USER");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("jwt", token));

        filter.doFilter(request, new MockHttpServletResponse(), passThroughChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("99");
    }

    @Test
    void leavesContextEmptyWhenNoTokenPresent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();

        filter.doFilter(request, new MockHttpServletResponse(), passThroughChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void leavesContextEmptyForMalformedToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer not-a-real-token");

        filter.doFilter(request, new MockHttpServletResponse(), passThroughChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
