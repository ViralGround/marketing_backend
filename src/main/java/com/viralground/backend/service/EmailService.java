package com.viralground.backend.service;

import com.viralground.backend.event.ApplicationResultEvent;
import com.viralground.backend.event.CreatorSignedUpEvent;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.notification.NotificationOutboxService;
import com.viralground.backend.notification.StagingEmailValidationTemplate;
import io.sentry.Sentry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.util.HtmlUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class EmailService {

    private static final String KIND_EMAIL_VERIFICATION_CODE = "EMAIL_VERIFICATION_CODE";
    private static final String KIND_PASSWORD_RESET_CODE = "PASSWORD_RESET_CODE";
    private static final String KIND_CREATOR_SIGNUP_ADMIN = "CREATOR_SIGNUP_ADMIN";
    private static final String KIND_MEMBER_STATUS_RESULT = "MEMBER_STATUS_RESULT";
    private static final String KIND_ESCROW_DEPOSIT_ADMIN = "ESCROW_DEPOSIT_ADMIN";
    private static final String KIND_ESCROW_FUNDED_COMPANY = "ESCROW_FUNDED_COMPANY";
    private static final String KIND_CAMPAIGN_APPLICATION_ADMIN = "CAMPAIGN_APPLICATION_ADMIN";
    private static final String KIND_CONTACT_RECEIVED_ADMIN = "CONTACT_RECEIVED_ADMIN";
    private static final String KIND_APPLICATION_RESULT = "APPLICATION_RESULT";
    private static final String KIND_APPLICATION_CHANGES_REQUESTED = "APPLICATION_CHANGES_REQUESTED";

    private final RestClient restClient;
    private final NotificationOutboxService outboxService;
    private final String from;
    private final List<String> adminEmails;
    private final boolean mockMode;
    private final DeliveryMode deliveryMode;
    private final Set<String> allowedRecipients;
    private final boolean paymentsEnabled;
    private final AtomicBoolean missingAdminSignaled = new AtomicBoolean();

    public EmailService(
            NotificationOutboxService outboxService,
            @Value("${resend.api-key:}") String apiKey,
            @Value("${resend.from:}") String from,
            @Value("${app.admin-emails:}") String adminEmailsRaw,
             @Value("${email.mock:false}") boolean mockMode,
             @Value("${email.delivery-mode:disabled}") String deliveryMode,
             @Value("${email.allowed-recipients:}") String allowedRecipientsRaw,
             @Value("${features.payments.enabled:false}") boolean paymentsEnabled) {
        this.outboxService = outboxService;
        this.mockMode = mockMode;
        this.deliveryMode = mockMode ? DeliveryMode.DISABLED : DeliveryMode.parse(deliveryMode);
        this.allowedRecipients = Arrays.stream(allowedRecipientsRaw.split(","))
                .map(EmailService::normalizeEmail)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.paymentsEnabled = paymentsEnabled;
        if (mockMode) {
            log.warn("═══════════════════════════════════════════════════════════════");
            log.warn("⚠️  EMAIL MOCK MODE ACTIVE — Resend 호출을 생략합니다");
            log.warn("   프로덕션에서는 절대 활성화하지 마세요 (EMAIL_MOCK=false).");
            log.warn("═══════════════════════════════════════════════════════════════");
        } else if (this.deliveryMode != DeliveryMode.DISABLED) {
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

        if (this.deliveryMode == DeliveryMode.ALLOWLIST && this.allowedRecipients.isEmpty()) {
            throw new IllegalStateException("EMAIL_DELIVERY_MODE=allowlist이면 EMAIL_ALLOWED_RECIPIENTS가 필요합니다.");
        }

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(3_000);
        requestFactory.setReadTimeout(8_000);
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl("https://api.resend.com")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private static String esc(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value);
    }

    public void queueVerificationCode(String to, String code, String idempotencyKey) {
        if (deliveryMode == DeliveryMode.DISABLED) {
            log.info("event=email_skipped reason=delivery_disabled type=verification");
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
        // 제목은 실패 로그나 공급자 메타데이터에 더 넓게 노출될 수 있으므로 인증 코드를 넣지 않는다.
        enqueueReplaceableCodeEmail(KIND_EMAIL_VERIFICATION_CODE, idempotencyKey,
                to, "[Viral Ground] 이메일 인증 코드", html, true);
    }

    public void queuePasswordResetCode(String to, String code, String idempotencyKey) {
        if (deliveryMode == DeliveryMode.DISABLED) {
            log.info("event=email_skipped reason=delivery_disabled type=password_reset");
            return;
        }
        String html = """
                <div style="font-family:system-ui,sans-serif;max-width:480px;margin:0 auto;padding:24px;color:#111;">
                  <h2 style="margin:0 0 16px;">비밀번호 재설정 코드</h2>
                  <p style="color:#444;margin:0 0 16px;">아래 6자리 코드를 비밀번호 재설정 화면에 입력해주세요.</p>
                  <div style="background:#f5f5f5;border-radius:8px;padding:20px;text-align:center;margin:20px 0;">
                    <span style="font-size:32px;font-weight:700;letter-spacing:8px;color:#111;font-family:monospace;">%s</span>
                  </div>
                  <p style="color:#888;font-size:13px;margin:16px 0 0;">이 코드는 <strong>5분</strong> 동안 유효합니다.</p>
                  <p style="color:#888;font-size:13px;margin:8px 0 0;">본인이 요청하지 않았다면 이 이메일을 무시하세요. 비밀번호는 변경되지 않습니다.</p>
                </div>
                """.formatted(code);
        // 제목은 실패 로그나 공급자 메타데이터에 더 넓게 노출될 수 있으므로 코드를 넣지 않는다.
        enqueueReplaceableCodeEmail(KIND_PASSWORD_RESET_CODE, idempotencyKey,
                to, "[Viral Ground] 비밀번호 재설정 코드", html, false);
    }

    /** Identity-only delivery decision used before creating a reset-code row. */
    public boolean canDeliverAuthenticationCode(String to) {
        return deliveryMode != DeliveryMode.DISABLED && recipientAllowed(to);
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onCreatorSignedUp(CreatorSignedUpEvent event) {
        notifyAdminsOfNewCreator(event.name(), event.email());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
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
        if (!hasAdminRecipients()) return;
        adminEmails.forEach(admin -> notifyAdminOfNewCreator(admin, name, email));
    }

    public void notifyCreatorOfStatusChange(String to, String name, String status) {
        String msg = "APPROVED".equals(status)
                ? "승인되었습니다. 이제 로그인하실 수 있습니다."
                : "반려되었습니다. 자세한 문의는 관리자에게 연락해주세요.";
        String html = "<p>안녕하세요, %s님!</p><p>Viral Ground 가입 신청이 %s</p>".formatted(esc(name), msg);
        enqueueEmail(KIND_MEMBER_STATUS_RESULT, to, "[Viral Ground] 가입 신청 결과 안내", html);
    }

    public void notifyAdminsOfEscrowDepositRequest(String campaignTitle, String companyName, Integer totalBudget) {
        if (!hasAdminRecipients()) return;
        String html = "<p>캠페인 <strong>%s</strong>의 예치금 입금 확인이 필요합니다.</p><p>기업: %s<br>금액: %,d원</p>"
                .formatted(esc(campaignTitle), esc(companyName), totalBudget);
        adminEmails.forEach(admin -> enqueueEmail(
                KIND_ESCROW_DEPOSIT_ADMIN, admin, "[Viral Ground] 예치금 입금 확인 요청", html));
    }

    public void notifyCompanyOfEscrowFunded(String to, String companyName, String campaignTitle) {
        String html = "<p>안녕하세요, %s님!</p><p>캠페인 <strong>%s</strong>의 예치금 입금이 확인되었습니다. 캠페인이 모집 상태로 전환되었어요.</p>"
                .formatted(esc(companyName), esc(campaignTitle));
        enqueueEmail(KIND_ESCROW_FUNDED_COMPANY, to, "[Viral Ground] 예치금 입금 확인 완료", html);
    }

    public void notifyAdminsOfNewApplication(String campaignTitle, String creatorName) {
        if (!hasAdminRecipients()) return;
        adminEmails.forEach(admin -> notifyAdminOfNewApplication(
                admin, campaignTitle, creatorName));
    }

    public void notifyAdminsOfNewContact(String email, String brandName, String contactName) {
        if (!hasAdminRecipients()) return;
        adminEmails.forEach(admin -> notifyAdminOfNewContact(
                admin, email, brandName, contactName));
    }

    /**
     * Queues one fixed synthetic message through the same rendering/outbox path
     * used by real notifications. No authentication code is created and no
     * business entity is read or changed.
     */
    public void queueValidationProbe(
            StagingEmailValidationTemplate template, String fixedRecipient) {
        if (template == null) throw new IllegalArgumentException("template is required");
        String idempotencyKey = NotificationOutboxService.newIdempotencyKey();
        switch (template) {
            case EMAIL_VERIFICATION_CODE -> queueVerificationCode(
                    fixedRecipient, "000000", idempotencyKey);
            case PASSWORD_RESET_CODE -> queuePasswordResetCode(
                    fixedRecipient, "000000", idempotencyKey);
            case CREATOR_SIGNUP_ADMIN -> notifyAdminOfNewCreator(
                    fixedRecipient, "[SYNTHETIC EMAIL VALIDATION]",
                    "synthetic.creator@example.invalid");
            case MEMBER_STATUS_RESULT -> notifyCreatorOfStatusChange(
                    fixedRecipient, "[SYNTHETIC EMAIL VALIDATION]", "APPROVED");
            case CAMPAIGN_APPLICATION_ADMIN -> notifyAdminOfNewApplication(
                    fixedRecipient, "[SYNTHETIC EMAIL VALIDATION]",
                    "[SYNTHETIC EMAIL VALIDATION]");
            case CONTACT_RECEIVED_ADMIN -> notifyAdminOfNewContact(
                    fixedRecipient, "synthetic.contact@example.invalid",
                    "[SYNTHETIC EMAIL VALIDATION]", "[SYNTHETIC]");
            case APPLICATION_RESULT -> notifyCreatorOfApplicationResult(
                    fixedRecipient, "[SYNTHETIC EMAIL VALIDATION]",
                    "[SYNTHETIC EMAIL VALIDATION]", "APPROVED", null, null);
            case APPLICATION_CHANGES_REQUESTED -> notifyCreatorOfChangesRequested(
                    fixedRecipient, "[SYNTHETIC EMAIL VALIDATION]",
                    "[SYNTHETIC EMAIL VALIDATION]",
                    "Synthetic validation feedback; no action is required.");
        }
    }

    private void notifyAdminOfNewCreator(String to, String name, String email) {
        String html = "<p>새 크레이터 가입 신청이 접수되었습니다.</p><p>이름: %s<br>이메일: %s</p>"
                .formatted(esc(name), esc(email));
        enqueueEmail(KIND_CREATOR_SIGNUP_ADMIN, to,
                "[Viral Ground] 새 크레이터 가입 신청", html);
    }

    private void notifyAdminOfNewApplication(
            String to, String campaignTitle, String creatorName) {
        String html = "<p>캠페인 <strong>%s</strong>에 %s 크레이터가 지원했습니다.</p>"
                .formatted(esc(campaignTitle), esc(creatorName));
        enqueueEmail(KIND_CAMPAIGN_APPLICATION_ADMIN, to,
                "[Viral Ground] 새 캠페인 지원", html);
    }

    private void notifyAdminOfNewContact(
            String to, String email, String brandName, String contactName) {
        String html = """
                <p>새 상담 신청이 접수되었습니다.</p>
                <p>이메일: %s<br>브랜드명: %s<br>담당자명: %s</p>
                <p style="color:#888;font-size:13px;">어드민 페이지에서 상세 확인 후 회신해주세요.</p>
                """.formatted(
                esc(email),
                esc(brandName),
                contactName == null || contactName.isBlank() ? "(미입력)" : esc(contactName));
        enqueueEmail(KIND_CONTACT_RECEIVED_ADMIN, to,
                "[Viral Ground] 새 상담 신청 — " + brandName, html);
    }

    public void notifyCreatorOfApplicationResult(String to, String name, String campaignTitle,
                                                 String status, Integer rewardAmount, String reviewComment) {
        String msg = switch (status) {
            case "APPROVED" -> "참여가 승인되었습니다. 캠페인 브리프의 작업 범위와 일정을 확인해 주세요.";
            case "SETTLED" -> paymentsEnabled
                    ? "영상이 승인되어 정산이 완료되었습니다. 지급 금액: %,d원".formatted(rewardAmount != null ? rewardAmount : 0)
                    : "레거시 완료 상태가 기록되었습니다. 현재 플랫폼에서 결제나 정산이 실행된 것은 아닙니다.";
            case "COMPLETED" -> "콘텐츠가 최종 승인되어 작업이 완료되었습니다. 결제나 정산이 실행된 것은 아닙니다.";
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
        enqueueEmail(KIND_APPLICATION_RESULT, to, "[Viral Ground] 캠페인 지원 결과", html);
    }

    public void notifyCreatorOfChangesRequested(String to, String name, String campaignTitle, String reviewComment) {
        String html = """
                <p>안녕하세요, %s님!</p>
                <p>캠페인 <strong>%s</strong> 영상 검토 결과 <strong>수정 요청</strong>이 도착했습니다.</p>
                <div style="background:#f8f9fa;border-left:3px solid #ffa500;padding:12px 16px;margin:16px 0;color:#555;">%s</div>
                <p>마이페이지에서 재제출 해주세요.</p>
                """.formatted(esc(name), esc(campaignTitle),
                reviewComment == null || reviewComment.isBlank() ? "사유가 전달되지 않았습니다." : esc(reviewComment));
        enqueueEmail(KIND_APPLICATION_CHANGES_REQUESTED, to, "[Viral Ground] 영상 수정 요청", html);
    }

    private void enqueueEmail(String notificationKind, String to, String subject, String html) {
        enqueueEmail(notificationKind, NotificationOutboxService.newIdempotencyKey(),
                to, subject, html, false);
    }

    private void enqueueReplaceableCodeEmail(String notificationKind, String idempotencyKey,
                                              String to, String subject, String html,
                                              boolean failIfBlocked) {
        if (deliveryMode == DeliveryMode.DISABLED) {
            log.info("event=email_skipped reason=delivery_disabled type=one_time_code");
            return;
        }
        if (!recipientAllowed(to)) {
            log.warn("event=email_suppressed reason=recipient_not_allowlisted kind={}", notificationKind);
            if (failIfBlocked) {
                throw new AppException(ErrorCode.EMAIL_SEND_FAILED);
            }
            return;
        }
        outboxService.supersedePendingAndEnqueue(notificationKind, idempotencyKey,
                normalizeEmail(to), subject, html);
    }

    private void enqueueEmail(String notificationKind, String idempotencyKey,
                              String to, String subject, String html, boolean failIfBlocked) {
        if (deliveryMode == DeliveryMode.DISABLED) {
            log.info("event=email_skipped reason=delivery_disabled type=notification");
            return;
        }
        if (!recipientAllowed(to)) {
            log.warn("event=email_suppressed reason=recipient_not_allowlisted kind={}", notificationKind);
            if (failIfBlocked) {
                throw new AppException(ErrorCode.EMAIL_SEND_FAILED);
            }
            return;
        }
        outboxService.enqueue(notificationKind, idempotencyKey,
                normalizeEmail(to), subject, html);
    }

    /** NotificationOutboxDispatcher 전용. 실패는 반드시 예외로 반환해 재시도/DLQ 상태를 남긴다. */
    public String deliverOutbox(String to, String subject, String html, String idempotencyKey) {
        if (deliveryMode == DeliveryMode.DISABLED) {
            throw new IllegalStateException("email delivery is disabled");
        }
        requireRecipientAllowed(to);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("outbox idempotency key is required");
        }
        return sendEmailInternal(to, subject, html, true, idempotencyKey);
    }

    private String sendEmailInternal(String to, String subject, String html, boolean throwOnFailure,
                                     String idempotencyKey) {
        if (deliveryMode == DeliveryMode.DISABLED || mockMode) {
            log.info("event=email_skipped reason=delivery_disabled");
            return null;
        }
        try {
            RestClient.RequestBodySpec request = restClient.post().uri("/emails");
            if (idempotencyKey != null) {
                request.header("Idempotency-Key", idempotencyKey);
            }
            ResponseEntity<Map> response = request
                    .body(Map.of("from", from, "to", List.of(to), "subject", subject, "html", html))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        /* swallow default throw so status+body can be handled below */
                    })
                    .toEntity(Map.class);
            if (response.getStatusCode().isError()) {
                log.warn("Resend API 오류: status={}", response.getStatusCode());
                if (throwOnFailure) throw new AppException(ErrorCode.EMAIL_SEND_FAILED);
                return null;
            }
            Object providerId = response.getBody() == null ? null : response.getBody().get("id");
            if (providerId == null || providerId.toString().isBlank()) {
                log.warn("event=email_delivery_missing_provider_id");
                if (throwOnFailure) throw new AppException(ErrorCode.EMAIL_SEND_FAILED);
                return null;
            }
            return providerId.toString();
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.warn("event=email_delivery_failed errorType={}", e.getClass().getSimpleName());
            if (throwOnFailure) throw new AppException(ErrorCode.EMAIL_SEND_FAILED);
            return null;
        }
    }

    private boolean hasAdminRecipients() {
        if (!adminEmails.isEmpty()) return true;
        if (missingAdminSignaled.compareAndSet(false, true)) {
            Sentry.captureMessage("notification_admin_recipients_missing");
            log.error("event=notification_admin_recipients_missing");
        }
        return false;
    }

    private boolean recipientAllowed(String recipient) {
        return deliveryMode == DeliveryMode.LIVE
                || (deliveryMode == DeliveryMode.ALLOWLIST
                && allowedRecipients.contains(normalizeEmail(recipient)));
    }

    private void requireRecipientAllowed(String recipient) {
        if (!recipientAllowed(recipient)) {
            log.warn("event=email_blocked reason=recipient_not_allowlisted");
            throw new AppException(ErrorCode.EMAIL_SEND_FAILED);
        }
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    enum DeliveryMode {
        DISABLED,
        ALLOWLIST,
        LIVE;

        static DeliveryMode parse(String raw) {
            try {
                return valueOf((raw == null ? "" : raw.trim()).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalStateException(
                        "EMAIL_DELIVERY_MODE는 disabled, allowlist, live 중 하나여야 합니다.", invalid);
            }
        }
    }
}
