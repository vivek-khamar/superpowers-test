package com.superpowers.test.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SignupRateLimitFilterTest {

    private static final String SIGNUP_PATH = "/api/v1/auth/signup";

    private RateLimitProperties properties;
    private SignupRateLimitFilter filter;
    private FilterChain passThroughChain;

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        properties.setMaxRequests(10);
        properties.setWindowSeconds(60);
        Clock clock = Clock.fixed(Instant.parse("2026-07-10T00:00:00Z"), ZoneOffset.UTC);
        filter = new SignupRateLimitFilter(properties, clock);
        passThroughChain = (req, res) -> ((MockHttpServletResponse) res).setStatus(200);
    }

    @Test
    void allowsUpToTheConfiguredLimitFromTheSameIp() throws Exception {
        for (int i = 0; i < 10; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(signupRequestFrom("10.0.0.1"), response, passThroughChain);

            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void throttlesTheEleventhRequestWithinTheWindow() throws Exception {
        for (int i = 0; i < 10; i++) {
            filter.doFilter(signupRequestFrom("10.0.0.2"), new MockHttpServletResponse(), passThroughChain);
        }

        MockHttpServletResponse eleventh = new MockHttpServletResponse();
        filter.doFilter(signupRequestFrom("10.0.0.2"), eleventh, passThroughChain);

        assertThat(eleventh.getStatus()).isEqualTo(429);
        assertThat(eleventh.getContentAsString()).contains("RATE_LIMIT_EXCEEDED");
    }

    @Test
    void tracksLimitsIndependentlyPerIp() throws Exception {
        for (int i = 0; i < 10; i++) {
            filter.doFilter(signupRequestFrom("10.0.0.3"), new MockHttpServletResponse(), passThroughChain);
        }

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(signupRequestFrom("10.0.0.4"), response, passThroughChain);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void ignoresRequestsToOtherPaths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, passThroughChain);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private MockHttpServletRequest signupRequestFrom(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", SIGNUP_PATH);
        request.setRemoteAddr(ip);
        return request;
    }
}
