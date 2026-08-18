package com.viralground.backend.controller;

import com.viralground.backend.config.AuthUser;
import com.viralground.backend.dto.account.MarketingConsentRequest;
import com.viralground.backend.dto.account.MarketingConsentResponse;
import com.viralground.backend.entity.Role;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.logging.AuditAction;
import com.viralground.backend.logging.AuditService;
import com.viralground.backend.service.MarketingConsentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountControllerTest {

    @Mock MarketingConsentService service;
    @Mock AuditService auditService;

    @Test
    void authenticatedMemberCanReadAndWithdrawMarketingConsent() {
        AuthUser user = new AuthUser(8, "member@example.test", Role.COMPANY, "Member");
        AccountController controller = new AccountController(service, auditService);
        MarketingConsentRequest request = new MarketingConsentRequest(false, null);
        MarketingConsentResponse expected = new MarketingConsentResponse(false, null);
        when(service.change(8, request)).thenReturn(expected);

        var response = controller.changeMarketingConsent(request, user);

        assertThat(response.getBody()).isEqualTo(expected);
        verify(auditService).record(user, AuditAction.MARKETING_CONSENT_CHANGED,
                "member", 8, "SUCCESS", "OPT_OUT");
    }

    @Test
    void unauthenticatedRequestFailsClosed() {
        AccountController controller = new AccountController(service, auditService);

        assertThatThrownBy(() -> controller.getMarketingConsent(null))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void getReturnsCurrentPreference() {
        AuthUser user = new AuthUser(3, "creator@example.test", Role.CREATOR, "Creator");
        LocalDateTime agreedAt = LocalDateTime.of(2026, 8, 13, 5, 0);
        when(service.get(3)).thenReturn(new MarketingConsentResponse(true, agreedAt));
        AccountController controller = new AccountController(service, auditService);

        var body = controller.getMarketingConsent(user).getBody();

        assertThat(body).isEqualTo(new MarketingConsentResponse(true, agreedAt));
    }
}
