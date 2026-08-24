package com.viralground.backend.instagram;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Prevents token material from being processed in a no-provider clone process. */
@Component
@ConditionalOnProperty(name = "instagram.provider", havingValue = "disabled")
public final class DisabledInstagramTokenCipher implements InstagramTokenCipher {
    @Override
    public String encrypt(String plaintext) {
        throw new IllegalStateException("Instagram token cipher is disabled");
    }

    @Override
    public String decrypt(String ciphertext) {
        throw new IllegalStateException("Instagram token cipher is disabled");
    }
}
