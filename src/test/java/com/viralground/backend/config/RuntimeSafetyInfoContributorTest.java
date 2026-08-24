package com.viralground.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeSafetyInfoContributorTest {

    @Test
    void exposesOnlyTheExactNonSecretMutationSafetyContract() {
        RuntimeSafetyState state = mock(RuntimeSafetyState.class);
        when(state.currentCloneVerification()).thenReturn(
                new RuntimeSafetyState.CloneVerification(
                        "sanitized", true, true, true, "seal-fingerprint",
                        true, "e2e-before-fingerprint"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("email.delivery-mode", "disabled")
                .withProperty("app.scheduling.enabled", "false")
                .withProperty("instagram.sync.enabled", "false")
                .withProperty("instagram.oauth-state.cleanup-enabled", "false")
                .withProperty("instagram.webhook.cleanup-enabled", "false")
                .withProperty("files.orphan-cleanup.enabled", "false")
                .withProperty("notification.outbox.enabled", "true")
                .withProperty("notification.outbox.dispatch-enabled", "false")
                .withProperty("features.payments.enabled", "false")
                .withProperty("features.instagram.enabled", "false")
                .withProperty("features.uploads.enabled", "false");
        RuntimeSafetyInfoContributor contributor =
                new RuntimeSafetyInfoContributor(state, environment);
        Info.Builder builder = new Info.Builder();

        contributor.contribute(builder);

        assertThat(builder.build().getDetails()).containsEntry("runtimeSafety", Map.of(
                "cloneKind", "sanitized",
                "sentinel", Map.of(
                        "releaseIdMatched", true,
                        "migrationEvidenceComplete", true,
                        "evidenceSealMatched", true,
                        "evidenceSealFingerprint", "seal-fingerprint",
                        "e2eBeforeEvidenceSealMatched", true,
                        "e2eBeforeEvidenceSealFingerprint", "e2e-before-fingerprint"),
                "emailDeliveryMode", "disabled",
                "scheduling", Map.of(
                        "globalEnabled", false,
                        "jobs", Map.of(
                                "instagramSync", false,
                                "instagramOauthStateCleanup", false,
                                "instagramWebhookCleanup", false,
                                "notificationOutboxDispatch", false,
                                "uploadOrphanCleanup", false)),
                "outbox", Map.of(
                        "enabled", true,
                        "dispatchEnabled", false),
                "features", Map.of(
                        "payments", false,
                        "instagram", false,
                        "uploads", false),
                "adminBootstrap", Map.of(
                        "enabled", false,
                        "credentialsConfigured", false),
                "mutationMode", Map.of(
                        "accountProvisioningEnabled", false,
                        "e2eEnabled", false,
                        "emailValidationEnabled", false,
                        "emailValidationRecipientConfigured", false)));
    }
}
