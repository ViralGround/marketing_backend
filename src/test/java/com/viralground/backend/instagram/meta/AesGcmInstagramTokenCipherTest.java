package com.viralground.backend.instagram.meta;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmInstagramTokenCipherTest {

    private final byte[] key = new byte[32];
    private final AesGcmInstagramTokenCipher cipher = new AesGcmInstagramTokenCipher(key, new SecureRandom());

    @Test
    void encryptsWithRandomNonceAndRoundTrips() {
        String first = cipher.encrypt("meta-token-secret");
        String second = cipher.encrypt("meta-token-secret");

        assertThat(first).startsWith("v1:").isNotEqualTo(second).doesNotContain("meta-token-secret");
        assertThat(cipher.decrypt(first)).isEqualTo("meta-token-secret");
        assertThat(cipher.decrypt(second)).isEqualTo("meta-token-secret");
    }

    @Test
    void rejectsTamperedCiphertext() {
        String encrypted = cipher.encrypt("meta-token-secret");
        String tampered = encrypted.substring(0, encrypted.length() - 1) + "A";
        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(com.viralground.backend.instagram.InstagramIntegrationException.class);
    }
}
