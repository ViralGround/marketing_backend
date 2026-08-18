package com.viralground.backend.controller;

import com.viralground.backend.config.AuthUser;
import com.viralground.backend.dto.account.MarketingConsentRequest;
import com.viralground.backend.dto.account.MarketingConsentResponse;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.logging.AuditAction;
import com.viralground.backend.logging.AuditService;
import com.viralground.backend.service.MarketingConsentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/account")
@RequiredArgsConstructor
public class AccountController {

    private final MarketingConsentService marketingConsentService;
    private final AuditService auditService;

    @GetMapping("/marketing-consent")
    public ResponseEntity<MarketingConsentResponse> getMarketingConsent(
            @AuthenticationPrincipal AuthUser authUser) {
        requireAuthenticated(authUser);
        return ResponseEntity.ok(marketingConsentService.get(authUser.getId()));
    }

    @PutMapping("/marketing-consent")
    public ResponseEntity<MarketingConsentResponse> changeMarketingConsent(
            @Valid @RequestBody MarketingConsentRequest request,
            @AuthenticationPrincipal AuthUser authUser) {
        requireAuthenticated(authUser);
        MarketingConsentResponse response = marketingConsentService.change(authUser.getId(), request);
        auditService.record(authUser, AuditAction.MARKETING_CONSENT_CHANGED,
                "member", authUser.getId(), "SUCCESS",
                response.optedIn() ? "OPT_IN" : "OPT_OUT");
        return ResponseEntity.ok(response);
    }

    private static void requireAuthenticated(AuthUser authUser) {
        if (authUser == null) throw new AppException(ErrorCode.FORBIDDEN);
    }
}
