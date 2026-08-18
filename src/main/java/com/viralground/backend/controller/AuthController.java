package com.viralground.backend.controller;

import com.viralground.backend.dto.auth.CompanySignupRequest;
import com.viralground.backend.dto.auth.EmailCodeRequest;
import com.viralground.backend.dto.auth.EmailCodeVerifyRequest;
import com.viralground.backend.dto.auth.LoginRequest;
import com.viralground.backend.dto.auth.PasswordResetCodeRequest;
import com.viralground.backend.dto.auth.PasswordResetConfirmRequest;
import com.viralground.backend.dto.auth.SignupRequest;
import com.viralground.backend.dto.auth.TokenResponse;
import com.viralground.backend.service.AuthService;
import com.viralground.backend.service.EmailVerificationService;
import com.viralground.backend.service.PasswordResetService;
import com.viralground.backend.service.RateLimitService;
import com.viralground.backend.service.AuthCookieService;
import com.viralground.backend.config.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;
    private final PasswordResetService passwordResetService;
    private final RateLimitService rateLimitService;
    private final AuthCookieService authCookieService;

    @PostMapping("/email/request-code")
    public ResponseEntity<Map<String, Object>> requestEmailCode(
            @Valid @RequestBody EmailCodeRequest req,
            HttpServletRequest httpRequest) {
        rateLimitService.consumeRequestCodeByIp(clientIp(httpRequest));
        rateLimitService.consumeRequestCodeByEmail(normalizeEmail(req.getEmail()));
        LocalDateTime expiresAt = emailVerificationService.requestCode(req.getEmail());
        return ResponseEntity.ok(Map.of(
                "message", "인증 코드를 발송했습니다",
                "expiresAt", expiresAt.atZone(ZoneOffset.UTC).toOffsetDateTime().toString()
        ));
    }

    @PostMapping("/email/verify-code")
    public ResponseEntity<Map<String, String>> verifyEmailCode(
            @Valid @RequestBody EmailCodeVerifyRequest req,
            HttpServletRequest httpRequest) {
        rateLimitService.consumeVerifyCodeByIp(clientIp(httpRequest));
        String verifiedToken = emailVerificationService.verifyCode(req.getEmail(), req.getCode());
        return ResponseEntity.ok(Map.of(
                "message", "이메일 인증이 완료되었습니다",
                "verifiedToken", verifiedToken
        ));
    }

    /**
     * 비밀번호 재설정 코드 발송. 계정 존재 여부를 누출하지 않도록
     * 가입 여부와 무관하게 항상 같은 안내로 응답한다.
     */
    @PostMapping("/password/request-code")
    public ResponseEntity<Map<String, Object>> requestPasswordResetCode(
            @Valid @RequestBody PasswordResetCodeRequest req,
            HttpServletRequest httpRequest) {
        rateLimitService.consumePasswordResetRequestByIp(clientIp(httpRequest));
        rateLimitService.consumePasswordResetRequestByEmail(normalizeEmail(req.getEmail()));
        LocalDateTime expiresAt = passwordResetService.requestCode(req.getEmail());
        return ResponseEntity.ok(Map.of(
                "message", "가입된 이메일이라면 재설정 코드를 발송했습니다",
                "expiresAt", expiresAt.atZone(ZoneOffset.UTC).toOffsetDateTime().toString()
        ));
    }

    @PostMapping("/password/reset")
    public ResponseEntity<Map<String, String>> resetPassword(
            @Valid @RequestBody PasswordResetConfirmRequest req,
            HttpServletRequest httpRequest) {
        rateLimitService.consumePasswordResetConfirmByIp(clientIp(httpRequest));
        passwordResetService.reset(req.getEmail(), req.getCode(), req.getNewPassword());
        return ResponseEntity.ok(Map.of(
                "message", "비밀번호가 변경되었습니다. 새 비밀번호로 로그인해주세요."
        ));
    }

    @PostMapping("/signup")
    public ResponseEntity<Map<String, String>> signup(@Valid @RequestBody SignupRequest req) {
        authService.signup(req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("message", "가입 신청이 완료되었습니다. 이메일을 확인해주세요."));
    }

    @PostMapping("/signup/company")
    public ResponseEntity<Map<String, String>> signupCompany(@Valid @RequestBody CompanySignupRequest req) {
        authService.signupCompany(req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("message", "가입이 완료되었습니다. 이메일 인증 후 로그인해주세요."));
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(
            @Valid @RequestBody LoginRequest req,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {
        rateLimitService.consumeLoginByIp(clientIp(httpRequest));
        TokenResponse tokens = authService.login(req);
        authCookieService.write(response, tokens);
        return ResponseEntity.ok(Map.of("message", "로그인되었습니다."));
    }

    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = cookieValue(request, "refresh_token");
        if (refreshToken == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        TokenResponse tokens = authService.refresh(refreshToken);
        authCookieService.write(response, tokens);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        authService.logout(cookieValue(request, "refresh_token"));
        authCookieService.clear(response);
        return ResponseEntity.noContent().build();
    }

    /**
     * 브라우저가 상태 변경 요청 전에 double-submit CSRF 쿠키를 발급받는 공개 부트스트랩 경로다.
     * 반환되는 값은 비밀이 아니며, 인증 토큰은 어떤 응답 본문에도 노출하지 않는다.
     */
    @GetMapping("/csrf")
    public ResponseEntity<Map<String, String>> csrf(CsrfToken token) {
        return ResponseEntity.ok(Map.of("token", token.getToken()));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(@AuthenticationPrincipal AuthUser authUser) {
        if (authUser == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(Map.of(
                "id", authUser.getId(),
                "email", authUser.getEmail(),
                "name", authUser.getName(),
                "role", authUser.getRole().name()
        ));
    }

    private String cookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        // server.forward-headers-strategy=native 설정에 의해 신뢰된 프록시 뒤에서는
        // Tomcat이 X-Forwarded-For를 해석해 getRemoteAddr()가 실제 클라이언트 IP를 반환한다.
        // 헤더를 직접 읽으면 프록시가 없는 환경에서 공격자가 위조한 값을 신뢰하게 되므로 사용 금지.
        return request.getRemoteAddr();
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
