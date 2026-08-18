package com.viralground.backend.instagram.webhook;

import com.viralground.backend.instagram.meta.MetaInstagramProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
public class MetaInstagramWebhookVerifier {
    private static final String PREFIX = "sha256=";
    private final byte[] appSecret;
    private final byte[] verifyToken;

    public MetaInstagramWebhookVerifier(MetaInstagramProperties properties) {
        this.appSecret = bytes(properties.appSecret());
        this.verifyToken = bytes(properties.webhookVerifyToken());
    }

    public boolean validSignature(byte[] payload, String signatureHeader) {
        if (payload == null || signatureHeader == null || !signatureHeader.startsWith(PREFIX)) {
            return false;
        }
        try {
            byte[] supplied = HexFormat.of().parseHex(signatureHeader.substring(PREFIX.length()));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret, "HmacSHA256"));
            return MessageDigest.isEqual(mac.doFinal(payload), supplied);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return false;
        }
    }

    public boolean validVerifyToken(String supplied) {
        return supplied != null && MessageDigest.isEqual(verifyToken, bytes(supplied));
    }

    private static byte[] bytes(String value) {
        return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    }
}
