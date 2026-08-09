package com.urlshortener.repository;

import com.urlshortener.domain.ShortUrl;
import com.urlshortener.domain.UrlStatus;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public class UrlRepository {

    private static final RowMapper<ShortUrl> ROW_MAPPER = (rs, rowNum) -> new ShortUrl(
            rs.getLong("id"),
            rs.getString("short_code"),
            rs.getString("original_url"),
            rs.getInt("is_custom_alias") == 1,
            UrlStatus.valueOf(rs.getString("status")),
            toInstant(rs.getString("created_at")),
            toInstant(rs.getString("updated_at")),
            toInstant(rs.getString("expires_at")),
            toInstant(rs.getString("last_accessed_at"))
    );

    private final JdbcTemplate jdbcTemplate;

    public UrlRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Inserts a new short URL row. Callers MUST be prepared to catch
     * {@link org.springframework.dao.DataAccessException} (a unique
     * constraint violation on {@code short_code} surfaces this way) - see
     * {@code UrlShortenerService} for the retry/conflict handling built on
     * top of this.
     */
    public void insert(ShortUrl url) {
        jdbcTemplate.update(
                "INSERT INTO urls (short_code, original_url, is_custom_alias, status, created_at, updated_at, expires_at, last_accessed_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                url.shortCode(),
                url.originalUrl(),
                url.customAlias() ? 1 : 0,
                url.status().name(),
                fromInstant(url.createdAt()),
                fromInstant(url.updatedAt()),
                fromInstant(url.expiresAt()),
                fromInstant(url.lastAccessedAt())
        );
    }

    public Optional<ShortUrl> findByShortCode(String shortCode) {
        try {
            ShortUrl url = jdbcTemplate.queryForObject(
                    "SELECT * FROM urls WHERE short_code = ?", ROW_MAPPER, shortCode);
            return Optional.ofNullable(url);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void updateDestination(String shortCode, String originalUrl, Instant expiresAt, Instant now) {
        jdbcTemplate.update(
                "UPDATE urls SET original_url = ?, expires_at = ?, updated_at = ? WHERE short_code = ?",
                originalUrl, fromInstant(expiresAt), fromInstant(now), shortCode
        );
    }

    public void updateStatus(String shortCode, UrlStatus status, Instant now) {
        jdbcTemplate.update(
                "UPDATE urls SET status = ?, updated_at = ? WHERE short_code = ?",
                status.name(), fromInstant(now), shortCode
        );
    }

    public void updateLastAccessed(String shortCode, Instant now) {
        jdbcTemplate.update(
                "UPDATE urls SET last_accessed_at = ? WHERE short_code = ?",
                fromInstant(now), shortCode
        );
    }

    private static String fromInstant(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static Instant toInstant(String value) {
        if (value == null) {
            return null;
        }
        // SQLite strftime produces "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" which Instant.parse
        // handles directly; values we write ourselves use Instant#toString (ISO-8601).
        return Instant.parse(value);
    }
}
