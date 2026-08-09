package com.urlshortener.exception;

public class ShortCodeNotFoundException extends RuntimeException {
    public ShortCodeNotFoundException(String shortCode) {
        super("No URL found for short code '" + shortCode + "'");
    }
}
