package com.urlshortener.service;

import com.urlshortener.config.AsyncConfig;
import com.urlshortener.domain.ClickEvent;
import com.urlshortener.repository.ClickEventRepository;
import com.urlshortener.repository.UrlRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Records click events off the redirect request thread. The redirect
 * response must not wait on this write, so every method here is
 * {@link Async} on a small bounded executor (see {@link AsyncConfig}).
 */
@Service
public class ClickTrackingService {

    private final ClickEventRepository clickEventRepository;
    private final UrlRepository urlRepository;

    public ClickTrackingService(ClickEventRepository clickEventRepository, UrlRepository urlRepository) {
        this.clickEventRepository = clickEventRepository;
        this.urlRepository = urlRepository;
    }

    @Async(AsyncConfig.CLICK_EVENT_EXECUTOR)
    public void recordClick(String shortCode, String referrer, String userAgent, String ipHash) {
        Instant now = Instant.now();
        clickEventRepository.insert(new ClickEvent(null, shortCode, now, referrer, userAgent, ipHash));
        urlRepository.updateLastAccessed(shortCode, now);
    }
}
