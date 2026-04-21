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
    private final String appUrl;
    private final List<String> adminEmails;

    public EmailService(
            @Value("${resend.api-key:}") String apiKey,
            @Value("${resend.from}") String from,
            @Value("${app.url}") String appUrl,
            @Value("${app.admin-emails:}") String adminEmailsRaw) {
        this.from = from;
        this.appUrl = appUrl;
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
    public void sendEmailVerification(String to, String name, String token) {
        String link = appUrl + "/verify-email/confirm?token=" + token;
        String html = """
                <p>안녕하세요, %s님!</p>
                <p>아래 버튼을 클릭해 이메일을 인증해주세요. 링크는 <strong>24시간</strong> 동안 유효합니다.</p>
                <a href="%s" style="display:inline-block;padding:12px 24px;background:#111;color:#fff;border-radius:8px;text-decoration:none;font-weight:600">이메일 인증하기</a>
                <p>버튼이 작동하지 않으면 아래 링크를 복사해 브라우저에 붙여넣어 주세요:<br>%s</p>
                """.formatted(name, link, link);
        sendEmail(to, "Viral Ground 이메일 인증", html);
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
