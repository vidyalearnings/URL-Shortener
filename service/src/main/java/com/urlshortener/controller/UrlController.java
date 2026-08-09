package com.urlshortener.controller;

import com.urlshortener.dto.AnalyticsResponse;
import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.dto.UpdateUrlRequest;
import com.urlshortener.dto.UrlResponse;
import com.urlshortener.service.AnalyticsService;
import com.urlshortener.service.UrlShortenerService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/urls")
public class UrlController {

    private final UrlShortenerService urlShortenerService;
    private final AnalyticsService analyticsService;

    public UrlController(UrlShortenerService urlShortenerService, AnalyticsService analyticsService) {
        this.urlShortenerService = urlShortenerService;
        this.analyticsService = analyticsService;
    }

    @PostMapping
    public ResponseEntity<UrlResponse> create(@Valid @RequestBody CreateUrlRequest request,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        UrlShortenerService.CreateResult result = urlShortenerService.createUrl(request, idempotencyKey);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.response());
    }

    @GetMapping("/{code}")
    public ResponseEntity<UrlResponse> get(@PathVariable String code) {
        return ResponseEntity.ok(urlShortenerService.getUrl(code));
    }

    @GetMapping("/{code}/analytics")
    public ResponseEntity<AnalyticsResponse> analytics(@PathVariable String code) {
        return ResponseEntity.ok(analyticsService.getAnalytics(code));
    }

    @PutMapping("/{code}")
    public ResponseEntity<UrlResponse> update(@PathVariable String code, @RequestBody UpdateUrlRequest request) {
        return ResponseEntity.ok(urlShortenerService.updateUrl(code, request));
    }

    @DeleteMapping("/{code}")
    public ResponseEntity<Void> delete(@PathVariable String code) {
        urlShortenerService.deleteUrl(code);
        return ResponseEntity.noContent().build();
    }
}
