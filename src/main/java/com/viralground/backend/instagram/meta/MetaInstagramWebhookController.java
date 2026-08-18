package com.viralground.backend.instagram.meta;

import com.viralground.backend.instagram.InstagramIntegrationException;
import com.viralground.backend.instagram.webhook.InstagramWebhookService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/instagram/meta/webhook")
@ConditionalOnProperty(name = "instagram.provider", havingValue = "meta")
public class MetaInstagramWebhookController {
    static final int MAX_WEBHOOK_PAYLOAD_BYTES = 1_048_576;

    private final InstagramWebhookService webhookService;

    public MetaInstagramWebhookController(InstagramWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verify(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String verifyToken,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if (!webhookService.verifyChallenge(mode, verifyToken) || challenge == null) {
            return ResponseEntity.status(403).body("forbidden");
        }
        return ResponseEntity.ok(challenge);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> receive(
            HttpServletRequest request,
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature) {
        byte[] rawPayload = readBoundedPayload(request);
        webhookService.accept(rawPayload, signature);
        return ResponseEntity.ok().build();
    }

    private byte[] readBoundedPayload(HttpServletRequest request) {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > MAX_WEBHOOK_PAYLOAD_BYTES) throw payloadTooLarge();
        try {
            // Content-Length가 없는 chunked 요청도 MAX+1까지만 읽어 메모리 사용을 제한한다.
            byte[] payload = request.getInputStream().readNBytes(MAX_WEBHOOK_PAYLOAD_BYTES + 1);
            if (payload.length > MAX_WEBHOOK_PAYLOAD_BYTES) throw payloadTooLarge();
            return payload;
        } catch (IOException readFailure) {
            throw new InstagramIntegrationException("INSTAGRAM_WEBHOOK_INVALID",
                    "webhook 요청 본문을 읽을 수 없습니다", org.springframework.http.HttpStatus.BAD_REQUEST,
                    readFailure);
        }
    }

    private InstagramIntegrationException payloadTooLarge() {
        return new InstagramIntegrationException("INSTAGRAM_WEBHOOK_PAYLOAD_TOO_LARGE",
                "webhook 요청 본문이 너무 큽니다",
                org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @ExceptionHandler(InstagramIntegrationException.class)
    public ResponseEntity<Map<String, String>> integrationError(InstagramIntegrationException error) {
        return ResponseEntity.status(error.getStatus())
                .body(Map.of("code", error.getCode(), "message", error.getMessage()));
    }
}
