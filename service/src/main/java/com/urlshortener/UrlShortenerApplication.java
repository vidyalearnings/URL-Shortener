package com.urlshortener;

import com.urlshortener.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class UrlShortenerApplication {

    public static void main(String[] args) {
        ensureDbDirectoryExists();
        SpringApplication.run(UrlShortenerApplication.class, args);
    }

    /**
     * The sqlite-jdbc driver does not create missing parent directories for its
     * database file - on a fresh checkout (where {@code data/} is gitignored and
     * doesn't exist yet) that makes startup fail before Spring even gets a chance
     * to run schema.sql. Create it up front, before the Spring context (and its
     * DataSource bean) is built.
     */
    private static void ensureDbDirectoryExists() {
        String dbPath = System.getenv().getOrDefault("DB_PATH", "./data/urlshortener.db");
        Path parent = Path.of(dbPath).toAbsolutePath().normalize().getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to create database directory: " + parent, e);
            }
        }
    }
}
