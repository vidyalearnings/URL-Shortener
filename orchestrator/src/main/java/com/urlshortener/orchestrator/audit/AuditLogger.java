package com.urlshortener.orchestrator.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Appends one JSON object per line (JSONL) to an audit file, flushing immediately after each
 * write so the file is always durable and readable by a concurrently-running {@code metrics}/
 * {@code report} CLI invocation.
 */
public class AuditLogger implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final Path logFile;
    private final BufferedWriter writer;
    private final Object lock = new Object();

    public AuditLogger(Path logFile) {
        this.logFile = logFile;
        try {
            if (logFile.getParent() != null) {
                Files.createDirectories(logFile.getParent());
            }
            this.writer = Files.newBufferedWriter(logFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open audit log: " + logFile, e);
        }
    }

    public Path getLogFile() {
        return logFile;
    }

    public void log(AuditEvent event) {
        synchronized (lock) {
            try {
                writer.write(MAPPER.writeValueAsString(event));
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to write audit event", e);
            }
        }
    }

    /** Reads and parses every event currently persisted in {@code auditFile}, in file order. */
    public static List<AuditEvent> readAll(Path auditFile) {
        List<AuditEvent> events = new ArrayList<>();
        if (!Files.exists(auditFile)) {
            return events;
        }
        try {
            for (String line : Files.readAllLines(auditFile)) {
                if (line.isBlank()) {
                    continue;
                }
                events.add(MAPPER.readValue(line, AuditEvent.class));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read audit log: " + auditFile, e);
        }
        return events;
    }

    @Override
    public void close() {
        synchronized (lock) {
            try {
                writer.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
