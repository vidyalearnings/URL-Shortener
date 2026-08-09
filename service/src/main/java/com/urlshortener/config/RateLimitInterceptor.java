package com.urlshortener.config;

import com.urlshortener.exception.RateLimitExceededException;
import com.urlshortener.service.RateLimiterService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Per-IP token-bucket rate limiting for the public API surface. See
 * {@link RateLimiterService} for the caveat that this is single-instance
 * only (no shared/Redis-backed state).
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiterService rateLimiterService;

    public RateLimitInterceptor(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String clientIp = resolveClientIp(request);
        if (!rateLimiterService.tryConsume(clientIp)) {
            throw new RateLimitExceededException("Rate limit exceeded for client " + clientIp);
        }
        return true;
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
