package com.viralground.backend.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Revalidates live sanitized-clone evidence immediately before a scheduled mutation. */
@Component
public final class PreproductionScheduledMutationGuard {

    private final RuntimeSafetyState runtimeSafetyState;
    private final Environment environment;

    public PreproductionScheduledMutationGuard(
            RuntimeSafetyState runtimeSafetyState,
            Environment environment) {
        this.runtimeSafetyState = runtimeSafetyState;
        this.environment = environment;
    }

    public void requireSafe() {
        if (!isPreproduction()) return;
        rejectUtilityMode();
        RuntimeSafetyState.CloneVerification verification = liveVerification();
        boolean provisioning = environment.getProperty(
                "app.staging.account-provisioning-enabled", Boolean.class, false);
        boolean e2e = environment.getProperty(
                "app.staging.e2e-mutation-enabled", Boolean.class, false);
        boolean emailValidation = environment.getProperty(
                "app.staging.email-validation-enabled", Boolean.class, false);
        boolean safe = migrationSafe(verification) && !emailValidation
                && provisioning != e2e
                && (provisioning || verification.e2eBeforeEvidenceSealMatched());
        if (!safe) {
            throw new IllegalStateException(
                    "scheduled preproduction mutation safety verification failed");
        }
    }

    /** Allows only outbox delivery in the sealed Resend validation window. */
    public void requireSafeForEmailDelivery() {
        if (!isPreproduction()) return;
        rejectUtilityMode();
        RuntimeSafetyState.CloneVerification verification = liveVerification();
        boolean provisioning = environment.getProperty(
                "app.staging.account-provisioning-enabled", Boolean.class, false);
        boolean e2e = environment.getProperty(
                "app.staging.e2e-mutation-enabled", Boolean.class, false);
        boolean emailValidation = environment.getProperty(
                "app.staging.email-validation-enabled", Boolean.class, false);
        int modes = (provisioning ? 1 : 0) + (e2e ? 1 : 0)
                + (emailValidation ? 1 : 0);
        boolean provisioningSafe = modes == 1 && provisioning;
        boolean emailValidationSafe = modes == 1 && emailValidation
                && verification.e2eBeforeEvidenceSealMatched()
                && "allowlist".equalsIgnoreCase(environment.getProperty(
                "email.delivery-mode", ""))
                && !environment.getProperty("email.allowed-recipients", "").isBlank()
                && environment.getProperty(
                "app.scheduling.enabled", Boolean.class, false)
                && environment.getProperty(
                "notification.outbox.enabled", Boolean.class, false)
                && environment.getProperty(
                "notification.outbox.dispatch-enabled", Boolean.class, false)
                && !environment.getProperty(
                "features.payments.enabled", Boolean.class, false)
                && !environment.getProperty(
                "features.instagram.enabled", Boolean.class, false)
                && !environment.getProperty(
                "features.uploads.enabled", Boolean.class, false);
        boolean onlyOutboxJobEnabled = !environment.getProperty(
                "instagram.sync.enabled", Boolean.class, false)
                && !environment.getProperty(
                "instagram.oauth-state.cleanup-enabled", Boolean.class, false)
                && !environment.getProperty(
                "instagram.webhook.cleanup-enabled", Boolean.class, false)
                && !environment.getProperty(
                "files.orphan-cleanup.enabled", Boolean.class, false);
        if (!migrationSafe(verification)
                || !(provisioningSafe || emailValidationSafe)
                || emailValidation && !onlyOutboxJobEnabled) {
            throw new IllegalStateException(
                    "scheduled preproduction email delivery safety verification failed");
        }
    }

    private boolean isPreproduction() {
        return "preproduction".equals(
                environment.getProperty("app.environment", "development"));
    }

    private void rejectUtilityMode() {
        if (environment.getProperty("app.migration-runner.enabled", Boolean.class, false)
                || environment.getProperty(
                "app.exact-compatibility.enabled", Boolean.class, false)) {
            throw new IllegalStateException(
                    "scheduled mutation is forbidden in guarded clone utility mode");
        }
    }

    private RuntimeSafetyState.CloneVerification liveVerification() {
        return runtimeSafetyState.currentCloneVerification();
    }

    private static boolean migrationSafe(
            RuntimeSafetyState.CloneVerification verification) {
        return "sanitized".equals(verification.cloneKind())
                && verification.releaseIdMatched()
                && verification.migrationEvidenceComplete()
                && verification.evidenceSealMatched();
    }
}
