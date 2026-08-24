package com.viralground.backend.service;

import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.notification.NotificationOutboxService;
import com.viralground.backend.notification.StagingEmailValidationTemplate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class EmailServiceDeliveryModeTest {

    @Test
    void allowlistQueuesNotificationOnlyForInternalRecipient() {
        NotificationOutboxService outbox = mock(NotificationOutboxService.class);
        EmailService service = service(outbox, "allowlist", "qa@viralground.kr");

        service.notifyCreatorOfStatusChange("qa@viralground.kr", "QA", "APPROVED");
        service.notifyCreatorOfStatusChange("outside@example.test", "Outside", "APPROVED");

        verify(outbox).enqueue(eq("MEMBER_STATUS_RESULT"), anyString(),
                eq("qa@viralground.kr"), eq("[Viral Ground] 가입 신청 결과 안내"), contains("QA"));
        verify(outbox, never()).enqueue(anyString(), anyString(),
                eq("outside@example.test"), anyString(), anyString());
    }

    @Test
    void verificationOutboxFailsClosedOutsideAllowlist() {
        EmailService service = service(mock(NotificationOutboxService.class),
                "allowlist", "qa@viralground.kr");

        assertThatThrownBy(() -> service.queueVerificationCode(
                "outside@example.test", "123456", "vg-outbox-test"))
                .isInstanceOf(AppException.class)
                .extracting(error -> ((AppException) error).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_SEND_FAILED);
    }

    @Test
    void verificationUsesReplaceableOutboxPath() {
        NotificationOutboxService outbox = mock(NotificationOutboxService.class);
        EmailService service = service(outbox, "allowlist", "qa@viralground.kr");

        service.queueVerificationCode("qa@viralground.kr", "123456", "vg-outbox-test");

        verify(outbox).supersedePendingAndEnqueue(eq("EMAIL_VERIFICATION_CODE"),
                eq("vg-outbox-test"), eq("qa@viralground.kr"),
                eq("[Viral Ground] 이메일 인증 코드"), contains("123456"));
    }

    @Test
    void passwordResetOutsideAllowlistIsSuppressedWithoutAccountOracle() {
        NotificationOutboxService outbox = mock(NotificationOutboxService.class);
        EmailService service = service(outbox, "allowlist", "qa@viralground.kr");

        assertThatCode(() -> service.queuePasswordResetCode(
                "outside@example.test", "123456", "vg-outbox-test"))
                .doesNotThrowAnyException();

        org.assertj.core.api.Assertions.assertThat(
                service.canDeliverAuthenticationCode("outside@example.test")).isFalse();
        verifyNoInteractions(outbox);
    }

    @Test
    void disabledModeCreatesNoOutboxForCodes() {
        NotificationOutboxService outbox = mock(NotificationOutboxService.class);
        EmailService service = new EmailService(outbox,
                "", "", "", false, "disabled", "", false);
        assertThatCode(() -> service.queueVerificationCode(
                "qa@example.test", "123456", "vg-outbox-test"))
                .doesNotThrowAnyException();
        verifyNoInteractions(outbox);
    }

    @Test
    void completedContentEmailExplicitlySaysNonfinancialCompletion() {
        NotificationOutboxService outbox = mock(NotificationOutboxService.class);
        EmailService service = service(outbox, "allowlist", "qa@viralground.kr");

        service.notifyCreatorOfApplicationResult(
                "qa@viralground.kr", "QA", "Campaign", "COMPLETED", null, null);

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(outbox).enqueue(eq("APPLICATION_RESULT"), anyString(),
                eq("qa@viralground.kr"), eq("[Viral Ground] 캠페인 지원 결과"), html.capture());
        org.assertj.core.api.Assertions.assertThat(html.getValue())
                .contains("콘텐츠가 최종 승인되어 작업이 완료되었습니다")
                .contains("결제나 정산이 실행된 것은 아닙니다")
                .doesNotContain("선정되지 않았거나 거절되었습니다");
    }

    @Test
    void approvedApplicationEmailDoesNotAdvertiseRewardInNontransactionalRelease() {
        NotificationOutboxService outbox = mock(NotificationOutboxService.class);
        EmailService service = service(outbox, "allowlist", "qa@viralground.kr");

        service.notifyCreatorOfApplicationResult(
                "qa@viralground.kr", "QA", "Campaign", "APPROVED", 500_000, null);

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(outbox).enqueue(eq("APPLICATION_RESULT"), anyString(),
                eq("qa@viralground.kr"), eq("[Viral Ground] 캠페인 지원 결과"), html.capture());
        org.assertj.core.api.Assertions.assertThat(html.getValue())
                .contains("작업 범위와 일정을 확인")
                .doesNotContain("500,000")
                .doesNotContain("보상 금액");
    }

    @Test
    void validationProbeCoversEveryPlannedNonfinancialTemplateAtFixedRecipient() {
        NotificationOutboxService outbox = mock(NotificationOutboxService.class);
        EmailService service = service(outbox, "allowlist", "qa@viralground.kr");

        for (StagingEmailValidationTemplate template
                : StagingEmailValidationTemplate.values()) {
            service.queueValidationProbe(template, "qa@viralground.kr");
        }

        ArgumentCaptor<String> replaceableKinds = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> replaceableRecipients = ArgumentCaptor.forClass(String.class);
        verify(outbox, times(2)).supersedePendingAndEnqueue(
                replaceableKinds.capture(), anyString(), replaceableRecipients.capture(),
                anyString(), anyString());
        ArgumentCaptor<String> notificationKinds = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> notificationRecipients = ArgumentCaptor.forClass(String.class);
        verify(outbox, times(6)).enqueue(
                notificationKinds.capture(), anyString(), notificationRecipients.capture(),
                anyString(), anyString());

        org.assertj.core.api.Assertions.assertThat(replaceableKinds.getAllValues())
                .containsExactlyInAnyOrder(
                        "EMAIL_VERIFICATION_CODE", "PASSWORD_RESET_CODE");
        org.assertj.core.api.Assertions.assertThat(notificationKinds.getAllValues())
                .containsExactlyInAnyOrder(
                        "CREATOR_SIGNUP_ADMIN", "MEMBER_STATUS_RESULT",
                        "CAMPAIGN_APPLICATION_ADMIN", "CONTACT_RECEIVED_ADMIN",
                        "APPLICATION_RESULT", "APPLICATION_CHANGES_REQUESTED")
                .noneMatch(kind -> kind.contains("ESCROW") || kind.contains("PAYMENT"));
        org.assertj.core.api.Assertions.assertThat(replaceableRecipients.getAllValues())
                .allMatch("qa@viralground.kr"::equals);
        org.assertj.core.api.Assertions.assertThat(notificationRecipients.getAllValues())
                .allMatch("qa@viralground.kr"::equals);
    }

    private EmailService service(NotificationOutboxService outbox, String mode, String allowed) {
        return new EmailService(outbox, "re_test_key", "qa@viralground.kr", "",
                false, mode, allowed, false);
    }
}
