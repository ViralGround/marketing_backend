package com.viralground.backend.instagram;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 로컬 mock 토큰 전용. 운영 provider에는 절대 등록되지 않는다. */
@Component
@ConditionalOnProperty(name = "instagram.provider", havingValue = "mock")
public class MockInstagramTokenCipher implements InstagramTokenCipher {
    private static final String PREFIX = "mock:";

    @Override
    public String encrypt(String plaintext) {
        return PREFIX + plaintext;
    }

    @Override
    public String decrypt(String ciphertext) {
        if (ciphertext == null || !ciphertext.startsWith(PREFIX)) {
            throw new IllegalArgumentException("유효하지 않은 mock token입니다");
        }
        return ciphertext.substring(PREFIX.length());
    }
}
