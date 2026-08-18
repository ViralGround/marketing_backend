package com.viralground.backend.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenTest {
    @Test
    void rotationMakesOldTokenUnusable() {
        RefreshToken token = new RefreshToken("old", 1, "family", Instant.now().plusSeconds(60));
        assertThat(token.isUsable(Instant.now())).isTrue();
        token.rotateTo("new");
        assertThat(token.isUsable(Instant.now())).isFalse();
        assertThat(token.getReplacedBy()).isEqualTo("new");
    }

    @Test
    void expiredTokenIsUnusable() {
        RefreshToken token = new RefreshToken("old", 1, "family", Instant.now().minusSeconds(1));
        assertThat(token.isUsable(Instant.now())).isFalse();
    }
}
