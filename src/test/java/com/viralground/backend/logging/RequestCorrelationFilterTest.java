package com.viralground.backend.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestCorrelationFilterTest {

    @Test
    void acceptsSafeExistingId() {
        assertThat(RequestCorrelationFilter.normalize("request-12345678"))
                .isEqualTo("request-12345678");
    }

    @Test
    void replacesUnsafeOrShortId() {
        assertThat(RequestCorrelationFilter.normalize("bad\nlog"))
                .matches("[0-9a-f-]{36}");
        assertThat(RequestCorrelationFilter.normalize("short"))
                .matches("[0-9a-f-]{36}");
    }
}
