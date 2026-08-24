package com.viralground.backend.controller;

import com.viralground.backend.dto.auth.LoginRequest;
import com.viralground.backend.dto.auth.PasswordResetCodeRequest;
import com.viralground.backend.dto.auth.TokenResponse;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.service.AuthService;
import com.viralground.backend.service.EmailVerificationService;
import com.viralground.backend.service.PasswordResetService;
import com.viralground.backend.service.RateLimitService;
import com.viralground.backend.service.AuthCookieService;
import com.viralground.backend.config.StagingAccountProvisioningPolicy;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthControllerCookieTest {
    private AuthService authService;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        AuthCookieService cookieService = new AuthCookieService(false, "", "Lax");
        controller = new AuthController(authService, mock(EmailVerificationService.class),
                mock(PasswordResetService.class), mock(RateLimitService.class), cookieService,
                mock(StagingAccountProvisioningPolicy.class));
    }

    @Test
    void loginStoresBothTokensInHttpOnlyCookiesAndNeverReturnsThemInBody() {
        LoginRequest request = new LoginRequest();
        request.setEmail("member@example.com");
        request.setPassword("long-password");
        when(authService.login(request)).thenReturn(new TokenResponse("access-secret", "refresh-secret"));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        var response = controller.login(request, new MockHttpServletRequest(), servletResponse);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("message", "로그인되었습니다.");
        assertThat(response.getBody().toString()).doesNotContain("access-secret", "refresh-secret");
        List<String> cookies = servletResponse.getHeaders("Set-Cookie");
        assertThat(cookies).hasSize(2).allMatch(value -> value.contains("HttpOnly"));
        assertThat(cookies).allMatch(value -> value.contains("SameSite=Lax"));
        assertThat(cookies).noneMatch(value -> value.contains("Secure"));
        assertThat(cookies).anyMatch(value -> value.startsWith("access_token=access-secret"));
        assertThat(cookies).anyMatch(value -> value.startsWith("refresh_token=refresh-secret"));
    }

    @Test
    void productionCookiesAreSecureAndShareConfiguredParentDomain() {
        controller = new AuthController(authService, mock(EmailVerificationService.class),
                mock(PasswordResetService.class), mock(RateLimitService.class),
                new AuthCookieService(true, ".viralground.example", "Lax"),
                mock(StagingAccountProvisioningPolicy.class));
        when(authService.login(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new TokenResponse("access-secret", "refresh-secret"));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        controller.login(new LoginRequest(), new MockHttpServletRequest(), servletResponse);

        assertThat(servletResponse.getHeaders("Set-Cookie"))
                .allMatch(value -> value.contains("Secure"))
                .allMatch(value -> value.contains("viralground.example"));
    }

    @Test
    void logoutExpiresBothHttpOnlyAuthCookies() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("refresh_token", "refresh-secret"));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        controller.logout(request, servletResponse);

        assertThat(servletResponse.getHeaders("Set-Cookie"))
                .hasSize(2)
                .allMatch(value -> value.contains("Max-Age=0"))
                .allMatch(value -> value.contains("HttpOnly"));
    }

    @Test
    void csrfBootstrapReturnsOnlyTheDoubleSubmitToken() {
        var response = controller.csrf(new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "csrf-value"));

        assertThat(response.getBody()).containsOnlyKeys("token").containsEntry("token", "csrf-value");
    }

    @Test
    void passwordResetRequestAppliesStagingIdentityPolicyBeforeLookup() {
        PasswordResetService resetService = mock(PasswordResetService.class);
        RateLimitService rateLimitService = mock(RateLimitService.class);
        StagingAccountProvisioningPolicy policy = mock(StagingAccountProvisioningPolicy.class);
        AuthController guardedController = new AuthController(
                authService, mock(EmailVerificationService.class), resetService,
                rateLimitService, new AuthCookieService(false, "", "Lax"), policy);
        PasswordResetCodeRequest request = new PasswordResetCodeRequest();
        request.setEmail("outside@example.com");
        doThrow(new AppException(ErrorCode.FORBIDDEN))
                .when(policy).requireAllowedEmail("outside@example.com");

        assertThatThrownBy(() -> guardedController.requestPasswordResetCode(
                request, new MockHttpServletRequest()))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FORBIDDEN);

        verify(policy).requireAllowedEmail("outside@example.com");
        verifyNoInteractions(resetService, rateLimitService);
    }
}
