package com.viralground.backend.instagram.webhook;

import com.viralground.backend.instagram.meta.MetaInstagramProperties;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MetaInstagramWebhookVerifierTest {

    private final MetaInstagramProperties properties = new MetaInstagramProperties(
            "app", "app-secret", "https://api.example/callback", "https://web.example/result",
            "key", "verify-token", "v25.0", null, null, null, List.of(), Duration.ofMinutes(10),
            Duration.ofSeconds(3), Duration.ofSeconds(8), Duration.ZERO, Duration.ofDays(7), 3, 50, 3, 14);
    private final MetaInstagramWebhookVerifier verifier = new MetaInstagramWebhookVerifier(properties);

    @Test
    void verifiesExactRawPayloadHmacAndRejectsMutation() throws Exception {
        byte[] payload = "{\"entry\":[{\"id\":\"1\"}]}".getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("app-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));

        assertThat(verifier.validSignature(payload, signature)).isTrue();
        assertThat(verifier.validSignature("{}".getBytes(StandardCharsets.UTF_8), signature)).isFalse();
        assertThat(verifier.validSignature(payload, "garbage")).isFalse();
    }

    @Test
    void verifyTokenUsesExactMatch() {
        assertThat(verifier.validVerifyToken("verify-token")).isTrue();
        assertThat(verifier.validVerifyToken("verify-token-x")).isFalse();
    }
}
