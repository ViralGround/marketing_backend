package com.viralground.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class EmailService {

    private final RestClient restClient;
    private final String from;
    private final List<String> adminEmails;

    public EmailService(
            @Value("${resend.api-key:}") String apiKey,
            @Value("${resend.from}") String from,
            @Value("${app.admin-emails:}") String adminEmailsRaw) {
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

    @Async
    public void sendVerificationCode(String to, String code) {
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
        sendEmail(to, "[Viral Ground] 이메일 인증 코드: " + code, html);
    }

    @Async
    public void notifyAdminsOfNewCreator(String name, String email) {
        if (adminEmails.isEmpty()) return;
        String html = "<p>새 크레이터 가입 신청이 접수되었습니다.</p><p>이름: %s<br>이메일: %s</p>".formatted(name, email);
        adminEmails.forEach(admin -> sendEmail(admin, "[Viral Ground] 새 크레이터 가입 신청", html));
    }

    @Async
    public void notifyCreatorOfStatusChange(String to, String name, String status) {
        String msg = "APPROVED".equals(status)
                ? "승인되었습니다. 이제 로그인하실 수 있습니다."
                : "반려되었습니다. 자세한 문의는 관리자에게 연락해주세요.";
        String html = "<p>안녕하세요, %s님!</p><p>Viral Ground 가입 신청이 %s</p>".formatted(name, msg);
        sendEmail(to, "[Viral Ground] 가입 신청 결과 안내", html);
    }

    @Async
    public void notifyAdminsOfEscrowDepositRequest(String campaignTitle, String companyName, Integer totalBudget) {
        if (adminEmails.isEmpty()) return;
        String html = "<p>캠페인 <strong>%s</strong>의 예치금 입금 확인이 필요합니다.</p><p>기업: %s<br>금액: %,d원</p>"
                .formatted(campaignTitle, companyName, totalBudget);
        adminEmails.forEach(admin -> sendEmail(admin, "[Viral Ground] 예치금 입금 확인 요청", html));
    }

    @Async
    public void notifyCompanyOfEscrowFunded(String to, String companyName, String campaignTitle) {
        String html = "<p>안녕하세요, %s님!</p><p>캠페인 <strong>%s</strong>의 예치금 입금이 확인되었습니다. 캠페인이 모집 상태로 전환되었어요.</p>"
                .formatted(companyName, campaignTitle);
        sendEmail(to, "[Viral Ground] 예치금 입금 확인 완료", html);
    }

    @Async
    public void notifyAdminsOfNewApplication(String campaignTitle, String creatorName) {
        if (adminEmails.isEmpty()) return;
        String html = "<p>캠페인 <strong>%s</strong>에 %s 크레이터가 지원했습니다.</p>".formatted(campaignTitle, creatorName);
        adminEmails.forEach(admin -> sendEmail(admin, "[Viral Ground] 새 캠페인 지원", html));
    }

    @Async
    public void notifyCreatorOfApplicationResult(String to, String name, String campaignTitle, String status, Integer rewardAmount) {
        String msg = "APPROVED".equals(status)
                ? "선정되었습니다! 보상 금액: %,d원".formatted(rewardAmount != null ? rewardAmount : 0)
                : "선정되지 않았습니다.";
        String html = "<p>안녕하세요, %s님!</p><p>캠페인 <strong>%s</strong> 지원 결과: %s</p>".formatted(name, campaignTitle, msg);
        sendEmail(to, "[Viral Ground] 캠페인 지원 결과", html);
    }

    private void sendEmail(String to, String subject, String html) {
        try {
            restClient.post()
                    .uri("/emails")
                    .body(Map.of("from", from, "to", List.of(to), "subject", subject, "html", html))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("이메일 발송 실패: to={}, subject={}, error={}", to, subject, e.getMessage());
        }
    }
}
