package com.superpowers.test.auth.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class SignupRateLimitFilter extends OncePerRequestFilter implements Ordered {

    private final RateLimitProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public SignupRateLimitFilter(RateLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    SignupRateLimitFilter(RateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!properties.getPath().equals(request.getRequestURI()) || !"POST".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(request);
        long nowMillis = clock.millis();
        long windowMillis = properties.getWindowSeconds() * 1000L;
        Window window = windows.computeIfAbsent(clientIp, ip -> new Window(nowMillis));

        if (window.incrementAndCheckExceeded(nowMillis, windowMillis, properties.getMaxRequests())) {
            writeTooManyRequests(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void writeTooManyRequests(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(
                "{\"errorCode\":\"RATE_LIMIT_EXCEEDED\",\"message\":\"Too many signup attempts. "
                        + "Please try again later.\"}");
    }

    private static final class Window {
        private long windowStartMillis;
        private int count;

        private Window(long windowStartMillis) {
            this.windowStartMillis = windowStartMillis;
            this.count = 0;
        }

        private synchronized boolean incrementAndCheckExceeded(long nowMillis, long windowMillis, int maxRequests) {
            if (nowMillis - windowStartMillis >= windowMillis) {
                windowStartMillis = nowMillis;
                count = 1;
                return false;
            }
            count++;
            return count > maxRequests;
        }
    }
}
