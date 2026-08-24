package com.viralground.backend.config;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Publishes the exact, non-secret safety switches that guard staging mutations. */
@Component
public final class RuntimeSafetyInfoContributor implements InfoContributor {
    private final RuntimeSafetyState runtimeSafetyState;
    private final Environment environment;

    public RuntimeSafetyInfoContributor(
            RuntimeSafetyState runtimeSafetyState,
            Environment environment) {
        this.runtimeSafetyState = runtimeSafetyState;
        this.environment = environment;
    }

    @Override
    public void contribute(Info.Builder builder) {
        RuntimeSafetyState.CloneVerification clone =
                runtimeSafetyState.currentCloneVerification();

        Map<String, Object> sentinel = new LinkedHashMap<>();
        sentinel.put("releaseIdMatched", clone.releaseIdMatched());
        sentinel.put("migrationEvidenceComplete", clone.migrationEvidenceComplete());
        sentinel.put("evidenceSealMatched", clone.evidenceSealMatched());
        sentinel.put("evidenceSealFingerprint", clone.evidenceSealFingerprint());
        sentinel.put("e2eBeforeEvidenceSealMatched",
                clone.e2eBeforeEvidenceSealMatched());
        sentinel.put("e2eBeforeEvidenceSealFingerprint",
                clone.e2eBeforeEvidenceSealFingerprint());

        Map<String, Object> jobs = new LinkedHashMap<>();
        jobs.put("instagramSync", bool("instagram.sync.enabled", false));
        jobs.put("instagramOauthStateCleanup",
                bool("instagram.oauth-state.cleanup-enabled", false));
        jobs.put("instagramWebhookCleanup",
                bool("instagram.webhook.cleanup-enabled", false));
        jobs.put("notificationOutboxDispatch",
                bool("notification.outbox.dispatch-enabled", false));
        jobs.put("uploadOrphanCleanup",
                bool("files.orphan-cleanup.enabled", false));

        Map<String, Object> scheduling = new LinkedHashMap<>();
        scheduling.put("globalEnabled", bool("app.scheduling.enabled", false));
        scheduling.put("jobs", jobs);

        Map<String, Object> outbox = new LinkedHashMap<>();
        outbox.put("enabled", bool("notification.outbox.enabled", true));
        outbox.put("dispatchEnabled",
                bool("notification.outbox.dispatch-enabled", false));

        Map<String, Object> features = new LinkedHashMap<>();
        features.put("payments", bool("features.payments.enabled", false));
        features.put("instagram", bool("features.instagram.enabled", false));
        features.put("uploads", bool("features.uploads.enabled", false));

        Map<String, Object> adminBootstrap = new LinkedHashMap<>();
        adminBootstrap.put("enabled", bool("admin.bootstrap.enabled", false));
        adminBootstrap.put("credentialsConfigured", adminBootstrapCredentialsConfigured());

        Map<String, Object> mutationMode = new LinkedHashMap<>();
        mutationMode.put("accountProvisioningEnabled",
                bool("app.staging.account-provisioning-enabled", false));
        mutationMode.put("e2eEnabled",
                bool("app.staging.e2e-mutation-enabled", false));
        mutationMode.put("emailValidationEnabled",
                bool("app.staging.email-validation-enabled", false));
        mutationMode.put("emailValidationRecipientConfigured",
                !environment.getProperty(
                        "app.staging.email-validation-recipient", "").isBlank());

        Map<String, Object> safety = new LinkedHashMap<>();
        safety.put("cloneKind", clone.cloneKind());
        safety.put("sentinel", sentinel);
        safety.put("emailDeliveryMode",
                environment.getProperty("email.delivery-mode", "disabled").trim().toLowerCase());
        safety.put("scheduling", scheduling);
        safety.put("outbox", outbox);
        safety.put("features", features);
        safety.put("adminBootstrap", adminBootstrap);
        safety.put("mutationMode", mutationMode);
        builder.withDetail("runtimeSafety", safety);
    }

    private boolean bool(String property, boolean defaultValue) {
        return environment.getProperty(property, Boolean.class, defaultValue);
    }

    private boolean adminBootstrapCredentialsConfigured() {
        return !environment.getProperty("admin.bootstrap.email", "").isBlank()
                || !environment.getProperty("admin.bootstrap.password", "").isBlank()
                || !environment.getProperty("admin.bootstrap.name", "").isBlank()
                || !environment.getProperty("admin.bootstrap.confirmation", "").isBlank();
    }
}
