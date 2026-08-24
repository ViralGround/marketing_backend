package com.viralground.backend.service;

import com.viralground.backend.config.PreproductionScheduledMutationGuard;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.notification.StagingEmailValidationTemplate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class StagingEmailValidationProbeServiceTest {

    @Test
    void sealedEmailWindowQueuesFixedRecipientAfterLiveGuard() {
        EmailService emailService = mock(EmailService.class);
        PreproductionScheduledMutationGuard guard =
                mock(PreproductionScheduledMutationGuard.class);
        StagingEmailValidationProbeService service = service(
                emailService, guard, "preproduction", true, false, false,
                "qa@viralground.kr", "other@viralground.kr,qa@viralground.kr");

        service.queue(StagingEmailValidationTemplate.CONTACT_RECEIVED_ADMIN);

        verify(guard).requireSafeForEmailDelivery();
        verify(emailService).queueValidationProbe(
                StagingEmailValidationTemplate.CONTACT_RECEIVED_ADMIN,
                "qa@viralground.kr");
    }

    @Test
    void nonPreproductionOrAnyOtherMutationWindowFailsBeforeQueueing() {
        for (StagingEmailValidationProbeService service : new StagingEmailValidationProbeService[]{
                service(mock(EmailService.class), mock(PreproductionScheduledMutationGuard.class),
                        "production", true, false, false,
                        "qa@viralground.kr", "qa@viralground.kr"),
                service(mock(EmailService.class), mock(PreproductionScheduledMutationGuard.class),
                        "preproduction", true, true, false,
                        "qa@viralground.kr", "qa@viralground.kr"),
                service(mock(EmailService.class), mock(PreproductionScheduledMutationGuard.class),
                        "preproduction", true, false, true,
                        "qa@viralground.kr", "qa@viralground.kr")}) {
            assertThatThrownBy(() -> service.queue(
                    StagingEmailValidationTemplate.EMAIL_VERIFICATION_CODE))
                    .isInstanceOf(AppException.class)
                    .extracting(error -> ((AppException) error).getErrorCode())
                    .isEqualTo(ErrorCode.EMAIL_VALIDATION_PROBE_DISABLED);
        }
    }

    @Test
    void recipientMustBeConfiguredInsideTheDeliveryAllowlist() {
        EmailService emailService = mock(EmailService.class);
        PreproductionScheduledMutationGuard guard =
                mock(PreproductionScheduledMutationGuard.class);
        StagingEmailValidationProbeService service = service(
                emailService, guard, "preproduction", true, false, false,
                "qa@viralground.kr", "other@viralground.kr");

        assertThatThrownBy(() -> service.queue(
                StagingEmailValidationTemplate.PASSWORD_RESET_CODE))
                .isInstanceOf(AppException.class)
                .extracting(error -> ((AppException) error).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_VALIDATION_PROBE_DISABLED);

        verify(guard, never()).requireSafeForEmailDelivery();
        verify(emailService, never()).queueValidationProbe(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    private static StagingEmailValidationProbeService service(
            EmailService emailService,
            PreproductionScheduledMutationGuard guard,
            String environment,
            boolean emailValidation,
            boolean provisioning,
            boolean e2e,
            String recipient,
            String allowedRecipients) {
        return new StagingEmailValidationProbeService(
                emailService, guard, environment, emailValidation, provisioning,
                e2e, recipient, allowedRecipients);
    }
}
