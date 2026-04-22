package com.viralground.backend.controller;

import com.viralground.backend.dto.auth.CompanySignupRequest;
import com.viralground.backend.dto.auth.EmailCodeRequest;
import com.viralground.backend.dto.auth.EmailCodeVerifyRequest;
import com.viralground.backend.dto.auth.LoginRequest;
import com.viralground.backend.dto.auth.SignupRequest;
import com.viralground.backend.dto.auth.TokenResponse;
import com.viralground.backend.service.AuthService;
import com.viralground.backend.service.EmailVerificationService;
import com.viralground.backend.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;
    private final RateLimitService rateLimitService;

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
    public ResponseEntity<TokenResponse> login(
            @Valid @RequestBody LoginRequest req,
            HttpServletRequest httpRequest) {
        rateLimitService.consumeLoginByIp(clientIp(httpRequest));
        return ResponseEntity.ok(authService.login(req));
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
