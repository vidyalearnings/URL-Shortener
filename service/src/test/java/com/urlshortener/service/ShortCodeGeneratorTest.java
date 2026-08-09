package com.urlshortener.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShortCodeGeneratorTest {

    private static final Pattern BASE62_PATTERN = Pattern.compile("^[A-Za-z0-9]+$");

    private final ShortCodeGenerator generator = new ShortCodeGenerator();

    @Test
    void generatesCodeOfRequestedLength() {
        String code = generator.generate(7);
        assertThat(code).hasSize(7);
    }

    @Test
    void generatesOnlyBase62Characters() {
        for (int i = 0; i < 200; i++) {
            String code = generator.generate(10);
            assertThat(BASE62_PATTERN.matcher(code).matches())
                    .as("code '%s' should only contain base62 characters", code)
                    .isTrue();
        }
    }

    @Test
    void respectsDifferentLengths() {
        assertThat(generator.generate(1)).hasSize(1);
        assertThat(generator.generate(2)).hasSize(2);
        assertThat(generator.generate(20)).hasSize(20);
    }

    @Test
    void rejectsNonPositiveLength() {
        assertThatThrownBy(() -> generator.generate(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.generate(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void producesVariedOutputAcrossCalls() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            codes.add(generator.generate(10));
        }
        // With 10-character base62 codes, 100 draws colliding would be astronomically
        // unlikely if randomness is working correctly.
        assertThat(codes).hasSizeGreaterThan(90);
    }
}
