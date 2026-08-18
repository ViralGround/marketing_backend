package com.viralground.backend.instagram.meta;

import com.viralground.backend.instagram.InstagramIntegrationException;
import com.viralground.backend.instagram.InstagramTokenCipher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
@ConditionalOnProperty(name = "instagram.provider", havingValue = "meta")
public class AesGcmInstagramTokenCipher implements InstagramTokenCipher {

    private static final byte[] AAD = "viralground:meta-instagram-token:v1"
            .getBytes(StandardCharsets.UTF_8);
    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random;

    public AesGcmInstagramTokenCipher(MetaInstagramProperties properties) {
        properties.requireConfigured();
        this.key = new SecretKeySpec(decodeKey(properties.tokenEncryptionKey()), "AES");
        this.random = new SecureRandom();
    }

    AesGcmInstagramTokenCipher(byte[] key, SecureRandom random) {
        if (key.length != 32) {
            throw new IllegalArgumentException("Instagram token encryption key must be 32 bytes");
        }
        this.key = new SecretKeySpec(key.clone(), "AES");
        this.random = random;
    }

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw cryptoFailure(null);
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(AAD);
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(packed);
        } catch (GeneralSecurityException e) {
            throw cryptoFailure(e);
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        if (ciphertext == null || !ciphertext.startsWith(PREFIX)) {
            throw cryptoFailure(null);
        }
        try {
            byte[] packed = Base64.getUrlDecoder().decode(ciphertext.substring(PREFIX.length()));
            if (packed.length <= IV_BYTES) {
                throw cryptoFailure(null);
            }
            byte[] iv = java.util.Arrays.copyOfRange(packed, 0, IV_BYTES);
            byte[] encrypted = java.util.Arrays.copyOfRange(packed, IV_BYTES, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw cryptoFailure(e);
        }
    }

    private static byte[] decodeKey(String encoded) {
        try {
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException ignored) {
                decoded = Base64.getUrlDecoder().decode(encoded);
            }
            if (decoded.length != 32) {
                throw new IllegalStateException("META_INSTAGRAM_TOKEN_ENCRYPTION_KEY는 Base64 32-byte 키여야 합니다");
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("META_INSTAGRAM_TOKEN_ENCRYPTION_KEY는 유효한 Base64여야 합니다", e);
        }
    }

    private static InstagramIntegrationException cryptoFailure(Throwable cause) {
        return new InstagramIntegrationException("INSTAGRAM_TOKEN_CRYPTO_FAILED",
                "인스타그램 연결 정보를 안전하게 처리하지 못했습니다", HttpStatus.SERVICE_UNAVAILABLE, cause);
    }
}
