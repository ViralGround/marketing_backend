package com.viralground.backend.service;

import com.viralground.backend.config.PreproductionScheduledMutationGuard;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.notification.StagingEmailValidationTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Queues only fixed synthetic messages during the sealed Resend validation window. */
@Service
@Slf4j
public class StagingEmailValidationProbeService {

    private final EmailService emailService;
    private final PreproductionScheduledMutationGuard mutationGuard;
    private final String appEnvironment;
    private final boolean emailValidationEnabled;
    private final boolean provisioningEnabled;
    private final boolean e2eEnabled;
    private final String recipient;
    private final Set<String> allowedRecipients;

    public StagingEmailValidationProbeService(
            EmailService emailService,
            PreproductionScheduledMutationGuard mutationGuard,
            @Value("${app.environment:development}") String appEnvironment,
            @Value("${app.staging.email-validation-enabled:false}")
            boolean emailValidationEnabled,
            @Value("${app.staging.account-provisioning-enabled:false}")
            boolean provisioningEnabled,
            @Value("${app.staging.e2e-mutation-enabled:false}") boolean e2eEnabled,
            @Value("${app.staging.email-validation-recipient:}") String recipient,
            @Value("${email.allowed-recipients:}") String allowedRecipientsRaw) {
        this.emailService = emailService;
        this.mutationGuard = mutationGuard;
        this.appEnvironment = appEnvironment == null ? "" : appEnvironment.trim();
        this.emailValidationEnabled = emailValidationEnabled;
        this.provisioningEnabled = provisioningEnabled;
        this.e2eEnabled = e2eEnabled;
        this.recipient = normalizeEmail(recipient);
        this.allowedRecipients = Arrays.stream(allowedRecipientsRaw.split(",", -1))
                .map(StagingEmailValidationProbeService::normalizeEmail)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** The only database write is the notification_outbox row created downstream. */
    @Transactional
    public void queue(StagingEmailValidationTemplate template) {
        if (!"preproduction".equals(appEnvironment)
                || !emailValidationEnabled || provisioningEnabled || e2eEnabled
                || recipient.isBlank() || !allowedRecipients.contains(recipient)) {
            throw new AppException(ErrorCode.EMAIL_VALIDATION_PROBE_DISABLED);
        }
        mutationGuard.requireSafeForEmailDelivery();
        emailService.queueValidationProbe(template, recipient);
        log.info("event=staging_email_validation_probe_queued template={}", template);
    }

    private static String normalizeEmail(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
