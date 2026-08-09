package com.urlshortener.repository;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public class IdempotencyKeyRepository {

    public record IdempotencyRecord(String idempotencyKey, String shortCode, String requestHash, Instant createdAt) {
    }

    private static final RowMapper<IdempotencyRecord> ROW_MAPPER = (rs, rowNum) -> new IdempotencyRecord(
            rs.getString("idempotency_key"),
            rs.getString("short_code"),
            rs.getString("request_hash"),
            Instant.parse(rs.getString("created_at"))
    );

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyKeyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<IdempotencyRecord> find(String key) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT * FROM idempotency_keys WHERE idempotency_key = ?", ROW_MAPPER, key));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void insert(String key, String shortCode, String requestHash) {
        jdbcTemplate.update(
                "INSERT INTO idempotency_keys (idempotency_key, short_code, request_hash, created_at) VALUES (?, ?, ?, ?)",
                key, shortCode, requestHash, Instant.now().toString()
        );
    }
}
