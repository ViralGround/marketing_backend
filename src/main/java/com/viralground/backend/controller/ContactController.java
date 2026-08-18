package com.viralground.backend.controller;

import com.viralground.backend.entity.ContactRequest;
import com.viralground.backend.service.ContactService;
import com.viralground.backend.service.RateLimitService;
import com.viralground.backend.logging.AuditAction;
import com.viralground.backend.logging.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/contact")
@RequiredArgsConstructor
public class ContactController {

    private final ContactService contactService;
    private final RateLimitService rateLimitService;
    private final AuditService auditService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> submit(@Valid @RequestBody ContactRequestDto dto,
                                                       HttpServletRequest request) {
        rateLimitService.consumeContactByIp(request.getRemoteAddr());
        if (!dto.privacyConsent()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "PRIVACY_CONSENT_REQUIRED",
                    "message", "개인정보 수집·이용 동의가 필요합니다."));
        }
        ContactRequest saved = contactService.submit(
                dto.email(), dto.brandName(), dto.contactName(), dto.privacyVersion());
        auditService.recordSystem(AuditAction.CONTACT_RECEIVED,
                "contactRequest", saved.getId(), "SUCCESS", null);
        return ResponseEntity.ok(Map.of(
                "id", saved.getId(),
                "message", "상담 신청이 접수되었습니다."));
    }

    public record ContactRequestDto(
            @Email(message = "올바른 이메일 형식이 아닙니다")
            @NotBlank(message = "이메일을 입력해주세요")
            @Size(max = 320)
            String email,

            @NotBlank(message = "브랜드명을 입력해주세요")
            @Size(max = 200)
            String brandName,

            @Size(max = 100)
            String contactName,

            boolean privacyConsent,

            @NotBlank(message = "개인정보 문서 버전이 필요합니다")
            @Size(max = 80)
            String privacyVersion,

            @Size(max = 0, message = "자동 제출이 감지되었습니다")
            String website
    ) {}
}
