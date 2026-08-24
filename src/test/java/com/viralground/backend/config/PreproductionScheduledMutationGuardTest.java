package com.viralground.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PreproductionScheduledMutationGuardTest {

    @Test
    void nonPreproductionDoesNotReadCloneEvidence() {
        RuntimeSafetyState state = mock(RuntimeSafetyState.class);
        PreproductionScheduledMutationGuard guard = new PreproductionScheduledMutationGuard(
                state, new MockEnvironment().withProperty("app.environment", "production"));

        assertThatCode(guard::requireSafe).doesNotThrowAnyException();
        verify(state, never()).currentCloneVerification();
    }

    @Test
    void preproductionFailsClosedWhenLiveEvidenceIsNotComplete() {
        RuntimeSafetyState state = mock(RuntimeSafetyState.class);
        when(state.currentCloneVerification()).thenReturn(
                new RuntimeSafetyState.CloneVerification(
                        "sanitized", true, false, true, "fingerprint", true, "e2e"));
        PreproductionScheduledMutationGuard guard = new PreproductionScheduledMutationGuard(
                state, e2eEnvironment());

        assertThatThrownBy(guard::requireSafe)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("safety verification failed");
    }

    @Test
    void preproductionE2eRequiresIndependentBeforeSeal() {
        RuntimeSafetyState state = mock(RuntimeSafetyState.class);
        when(state.currentCloneVerification()).thenReturn(
                new RuntimeSafetyState.CloneVerification(
                        "sanitized", true, true, true, "fingerprint", false, ""));
        PreproductionScheduledMutationGuard guard = new PreproductionScheduledMutationGuard(
                state, e2eEnvironment());

        assertThatThrownBy(guard::requireSafe).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void completedLiveSanitizedE2eCloneMayReachScheduledMutation() {
        RuntimeSafetyState state = mock(RuntimeSafetyState.class);
        when(state.currentCloneVerification()).thenReturn(
                new RuntimeSafetyState.CloneVerification(
                        "sanitized", true, true, true,
                        "fingerprint", true, "e2e-fingerprint"));
        PreproductionScheduledMutationGuard guard = new PreproductionScheduledMutationGuard(
                state, e2eEnvironment());

        assertThatCode(guard::requireSafe).doesNotThrowAnyException();
    }

    @Test
    void sealedEmailValidationWindowAllowsOnlyEmailDeliveryGuard() {
        RuntimeSafetyState state = mock(RuntimeSafetyState.class);
        when(state.currentCloneVerification()).thenReturn(safeVerification());
        MockEnvironment environment = emailValidationEnvironment();
        PreproductionScheduledMutationGuard guard =
                new PreproductionScheduledMutationGuard(state, environment);

        assertThatCode(guard::requireSafeForEmailDelivery).doesNotThrowAnyException();
        assertThatThrownBy(guard::requireSafe).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void emailValidationWindowRejectsOtherScheduledJobs() {
        RuntimeSafetyState state = mock(RuntimeSafetyState.class);
        when(state.currentCloneVerification()).thenReturn(safeVerification());
        MockEnvironment environment = emailValidationEnvironment()
                .withProperty("instagram.sync.enabled", "true");
        PreproductionScheduledMutationGuard guard =
                new PreproductionScheduledMutationGuard(state, environment);

        assertThatThrownBy(guard::requireSafeForEmailDelivery)
                .isInstanceOf(IllegalStateException.class);
    }

    private MockEnvironment e2eEnvironment() {
        return new MockEnvironment()
                .withProperty("app.environment", "preproduction")
                .withProperty("app.staging.account-provisioning-enabled", "false")
                .withProperty("app.staging.e2e-mutation-enabled", "true");
    }

    private MockEnvironment emailValidationEnvironment() {
        return new MockEnvironment()
                .withProperty("app.environment", "preproduction")
                .withProperty("app.staging.email-validation-enabled", "true")
                .withProperty("email.delivery-mode", "allowlist")
                .withProperty("email.allowed-recipients", "qa@viralground.kr")
                .withProperty("app.scheduling.enabled", "true")
                .withProperty("notification.outbox.enabled", "true")
                .withProperty("notification.outbox.dispatch-enabled", "true");
    }

    private RuntimeSafetyState.CloneVerification safeVerification() {
        return new RuntimeSafetyState.CloneVerification(
                "sanitized", true, true, true,
                "fingerprint", true, "e2e-fingerprint");
    }
}
