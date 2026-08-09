package com.urlshortener.service;

import com.urlshortener.domain.ShortUrl;
import com.urlshortener.dto.AnalyticsResponse;
import com.urlshortener.exception.ShortCodeNotFoundException;
import com.urlshortener.repository.ClickEventRepository;
import com.urlshortener.repository.UrlRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AnalyticsService {

    private static final int TOP_N = 10;

    private final UrlRepository urlRepository;
    private final ClickEventRepository clickEventRepository;

    public AnalyticsService(UrlRepository urlRepository, ClickEventRepository clickEventRepository) {
        this.urlRepository = urlRepository;
        this.clickEventRepository = clickEventRepository;
    }

    public AnalyticsResponse getAnalytics(String shortCode) {
        ShortUrl url = urlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new ShortCodeNotFoundException(shortCode));

        long totalClicks = clickEventRepository.countByShortCode(shortCode);
        List<AnalyticsResponse.DailyCount> clicksPerDay = clickEventRepository.countByDay(shortCode).stream()
                .map(e -> new AnalyticsResponse.DailyCount(e.getKey(), e.getValue()))
                .toList();
        Map<String, Long> referrers = clickEventRepository.topReferrers(shortCode, TOP_N);
        Map<String, Long> userAgents = clickEventRepository.topUserAgents(shortCode, TOP_N);

        return new AnalyticsResponse(shortCode, totalClicks, clicksPerDay, referrers, userAgents, url.lastAccessedAt());
    }
}
