package com.viralground.backend.service;

import com.viralground.backend.event.ApplicationResultEvent;
import com.viralground.backend.event.CreatorSignedUpEvent;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.HtmlUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class EmailService {

    private final RestClient restClient;
    private final String from;
    private final List<String> adminEmails;
    private final boolean mockMode;

    public EmailService(
            @Value("${resend.api-key:}") String apiKey,
            @Value("${resend.from:}") String from,
            @Value("${app.admin-emails:}") String adminEmailsRaw,
            @Value("${email.mock:false}") boolean mockMode) {
        this.mockMode = mockMode;
        if (mockMode) {
            log.warn("═══════════════════════════════════════════════════════════════");
            log.warn("⚠️  EMAIL MOCK MODE ACTIVE — Resend 호출 스킵, 로그에 코드 출력");
            log.warn("   프로덕션에서는 절대 활성화하지 마세요 (EMAIL_MOCK=false).");
            log.warn("═══════════════════════════════════════════════════════════════");
        } else {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException(
                        "RESEND_API_KEY 환경변수가 설정되지 않았습니다. 배포 환경(Railway) 또는 로컬 .env에 추가하세요. (개발/데모용이면 EMAIL_MOCK=true)");
            }
            if (from == null || from.isBlank()) {
                throw new IllegalStateException(
                        "EMAIL_FROM(resend.from) 설정이 비어있습니다. 배포 환경 또는 .env에 추가하세요.");
            }
        }
        this.from = from;
        this.adminEmails = Arrays.stream(adminEmailsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        this.restClient = RestClient.builder()
                .baseUrl("https://api.resend.com")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private static String esc(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value);
    }

    public void sendVerificationCode(String to, String code) {
        if (mockMode) {
            log.warn("═══════════════════════════════════════════");
            log.warn("[MOCK] 이메일 인증 코드 (Resend 스킵)");
            log.warn("  수신자: {}", to);
            log.warn("  코드  : {}", code);
            log.warn("═══════════════════════════════════════════");
            return;
        }
        String html = """
                <div style="font-family:system-ui,sans-serif;max-width:480px;margin:0 auto;padding:24px;color:#111;">
                  <h2 style="margin:0 0 16px;">이메일 인증 코드</h2>
                  <p style="color:#444;margin:0 0 16px;">아래 6자리 인증 코드를 가입 화면에 입력해주세요.</p>
                  <div style="background:#f5f5f5;border-radius:8px;padding:20px;text-align:center;margin:20px 0;">
                    <span style="font-size:32px;font-weight:700;letter-spacing:8px;color:#111;font-family:monospace;">%s</span>
                  </div>
                  <p style="color:#888;font-size:13px;margin:16px 0 0;">이 코드는 <strong>5분</strong> 동안 유효합니다.</p>
                  <p style="color:#888;font-size:13px;margin:8px 0 0;">본인이 요청하지 않았다면 이 이메일을 무시하세요.</p>
                </div>
                """.formatted(code);
        sendEmailOrThrow(to, "[Viral Ground] 이메일 인증 코드: " + code, html);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreatorSignedUp(CreatorSignedUpEvent event) {
        notifyAdminsOfNewCreator(event.name(), event.email());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onApplicationResult(ApplicationResultEvent event) {
        if ("CHANGES_REQUESTED".equals(event.status())) {
            notifyCreatorOfChangesRequested(
                    event.creatorEmail(),
                    event.creatorName(),
                    event.campaignTitle(),
                    event.reviewComment());
            return;
        }
        notifyCreatorOfApplicationResult(
                event.creatorEmail(),
                event.creatorName(),
                event.campaignTitle(),
                event.status(),
                event.rewardAmount(),
                event.reviewComment());
    }

    public void notifyAdminsOfNewCreator(String name, String email) {
        if (adminEmails.isEmpty()) return;
        String html = "<p>새 크레이터 가입 신청이 접수되었습니다.</p><p>이름: %s<br>이메일: %s</p>"
                .formatted(esc(name), esc(email));
        adminEmails.forEach(admin -> sendEmail(admin, "[Viral Ground] 새 크레이터 가입 신청", html));
    }

    @Async
    public void notifyCreatorOfStatusChange(String to, String name, String status) {
        String msg = "APPROVED".equals(status)
                ? "승인되었습니다. 이제 로그인하실 수 있습니다."
                : "반려되었습니다. 자세한 문의는 관리자에게 연락해주세요.";
        String html = "<p>안녕하세요, %s님!</p><p>Viral Ground 가입 신청이 %s</p>".formatted(esc(name), msg);
        sendEmail(to, "[Viral Ground] 가입 신청 결과 안내", html);
    }

    @Async
    public void notifyAdminsOfEscrowDepositRequest(String campaignTitle, String companyName, Integer totalBudget) {
        if (adminEmails.isEmpty()) return;
        String html = "<p>캠페인 <strong>%s</strong>의 예치금 입금 확인이 필요합니다.</p><p>기업: %s<br>금액: %,d원</p>"
                .formatted(esc(campaignTitle), esc(companyName), totalBudget);
        adminEmails.forEach(admin -> sendEmail(admin, "[Viral Ground] 예치금 입금 확인 요청", html));
    }

    @Async
    public void notifyCompanyOfEscrowFunded(String to, String companyName, String campaignTitle) {
        String html = "<p>안녕하세요, %s님!</p><p>캠페인 <strong>%s</strong>의 예치금 입금이 확인되었습니다. 캠페인이 모집 상태로 전환되었어요.</p>"
                .formatted(esc(companyName), esc(campaignTitle));
        sendEmail(to, "[Viral Ground] 예치금 입금 확인 완료", html);
    }

    @Async
    public void notifyAdminsOfNewApplication(String campaignTitle, String creatorName) {
        if (adminEmails.isEmpty()) return;
        String html = "<p>캠페인 <strong>%s</strong>에 %s 크레이터가 지원했습니다.</p>"
                .formatted(esc(campaignTitle), esc(creatorName));
        adminEmails.forEach(admin -> sendEmail(admin, "[Viral Ground] 새 캠페인 지원", html));
    }

    @Async
    public void notifyCreatorOfApplicationResult(String to, String name, String campaignTitle,
                                                 String status, Integer rewardAmount, String reviewComment) {
        String msg = switch (status) {
            case "APPROVED" -> "선정되었습니다! 보상 금액: %,d원".formatted(rewardAmount != null ? rewardAmount : 0);
            case "SETTLED" -> "영상이 승인되어 정산이 완료되었습니다. 지급 금액: %,d원".formatted(rewardAmount != null ? rewardAmount : 0);
            case "REJECTED" -> {
                String base = "선정되지 않았거나 거절되었습니다.";
                yield (reviewComment != null && !reviewComment.isBlank())
                        ? base + "<br/>사유: " + esc(reviewComment)
                        : base;
            }
            default -> "선정되지 않았거나 거절되었습니다.";
        };
        String html = "<p>안녕하세요, %s님!</p><p>캠페인 <strong>%s</strong> 지원 결과: %s</p>"
                .formatted(esc(name), esc(campaignTitle), msg);
        sendEmail(to, "[Viral Ground] 캠페인 지원 결과", html);
    }

    public void notifyCreatorOfChangesRequested(String to, String name, String campaignTitle, String reviewComment) {
        String html = """
                <p>안녕하세요, %s님!</p>
                <p>캠페인 <strong>%s</strong> 영상 검토 결과 <strong>수정 요청</strong>이 도착했습니다.</p>
                <div style="background:#f8f9fa;border-left:3px solid #ffa500;padding:12px 16px;margin:16px 0;color:#555;">%s</div>
                <p>마이페이지에서 재제출 해주세요.</p>
                """.formatted(esc(name), esc(campaignTitle),
                reviewComment == null || reviewComment.isBlank() ? "사유가 전달되지 않았습니다." : esc(reviewComment));
        sendEmail(to, "[Viral Ground] 영상 수정 요청", html);
    }

    private void sendEmail(String to, String subject, String html) {
        sendEmailInternal(to, subject, html, false);
    }

    private void sendEmailOrThrow(String to, String subject, String html) {
        sendEmailInternal(to, subject, html, true);
    }

    private void sendEmailInternal(String to, String subject, String html, boolean throwOnFailure) {
        if (mockMode) {
            log.info("[MOCK] 이메일 스킵: to={}, subject={}", to, subject);
            return;
        }
        try {
            ResponseEntity<String> response = restClient.post()
                    .uri("/emails")
                    .body(Map.of("from", from, "to", List.of(to), "subject", subject, "html", html))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        /* swallow default throw so status+body can be handled below */
                    })
                    .toEntity(String.class);
            if (response.getStatusCode().isError()) {
                log.warn("Resend API 오류: status={}, to={}, body={}",
                        response.getStatusCode(), to, response.getBody());
                if (throwOnFailure) throw new AppException(ErrorCode.EMAIL_SEND_FAILED);
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.warn("이메일 발송 실패(네트워크/기타): to={}, subject={}, error={}",
                    to, subject, e.getMessage());
            if (throwOnFailure) throw new AppException(ErrorCode.EMAIL_SEND_FAILED);
        }
    }
}
