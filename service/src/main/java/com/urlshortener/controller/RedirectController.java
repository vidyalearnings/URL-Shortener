package com.urlshortener.controller;

import com.urlshortener.domain.ShortUrl;
import com.urlshortener.service.ClickTrackingService;
import com.urlshortener.service.UrlShortenerService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@RestController
public class RedirectController {

    private final UrlShortenerService urlShortenerService;
    private final ClickTrackingService clickTrackingService;

    public RedirectController(UrlShortenerService urlShortenerService, ClickTrackingService clickTrackingService) {
        this.urlShortenerService = urlShortenerService;
        this.clickTrackingService = clickTrackingService;
    }

    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
        ShortUrl url = urlShortenerService.resolveForRedirect(code);

        // Fire-and-forget: the redirect response must not wait on the click write.
        clickTrackingService.recordClick(
                code,
                request.getHeader("Referer"),
                request.getHeader("User-Agent"),
                hashIp(request.getRemoteAddr())
        );

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(url.originalUrl()))
                .build();
    }

    private String hashIp(String ip) {
        if (ip == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(ip.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }
}
