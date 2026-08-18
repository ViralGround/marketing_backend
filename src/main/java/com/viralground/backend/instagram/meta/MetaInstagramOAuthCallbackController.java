package com.viralground.backend.instagram.meta;

import com.viralground.backend.instagram.InstagramIntegrationException;
import com.viralground.backend.service.InstagramConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/** Meta가 호출하는 비인증 OAuth callback. 결과는 안전한 코드만 포함해 프론트로 303 redirect한다. */
@RestController
@RequestMapping("/instagram/meta/oauth")
public class MetaInstagramOAuthCallbackController {

    private static final Logger log = LoggerFactory.getLogger(MetaInstagramOAuthCallbackController.class);

    private final InstagramConnectionService connectionService;
    private final MetaInstagramProperties properties;

    public MetaInstagramOAuthCallbackController(InstagramConnectionService connectionService,
                                                MetaInstagramProperties properties) {
        this.connectionService = connectionService;
        this.properties = properties;
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error) {
        try {
            if (error != null) {
                connectionService.cancelAuthorization(state);
                String result = "access_denied".equals(error) ? "cancelled" : "error";
                String reason = "access_denied".equals(error) ? null : "provider_rejected";
                return redirect(result, reason);
            }
            connectionService.completeAuthorization(state, code);
            return redirect("connected", null);
        } catch (InstagramIntegrationException integrationError) {
            String reason = publicReason(integrationError.getCode());
            log.warn("event=instagram_oauth_callback_error code={}", integrationError.getCode());
            return redirect("error", reason);
        } catch (RuntimeException unexpected) {
            log.error("event=instagram_oauth_callback_error code=UNEXPECTED", unexpected);
            return redirect("error", "temporary_failure");
        }
    }

    private ResponseEntity<Void> redirect(String result, String reason) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(properties.frontendResultUrl())
                .queryParam("instagram", result);
        if (reason != null) {
            builder.queryParam("reason", reason);
        }
        URI location = builder.build().encode().toUri();
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(location).build();
    }

    private static String publicReason(String code) {
        return switch (code) {
            case "INSTAGRAM_INVALID_STATE" -> "invalid_state";
            case "INSTAGRAM_ACCOUNT_MISMATCH", "INSTAGRAM_ACCOUNT_OWNERSHIP_FAILED" -> "account_mismatch";
            case "INSTAGRAM_PROFILE_HANDLE_REQUIRED" -> "profile_required";
            default -> "temporary_failure";
        };
    }
}
