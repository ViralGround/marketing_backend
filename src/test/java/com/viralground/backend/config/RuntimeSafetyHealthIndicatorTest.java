package com.viralground.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeSafetyHealthIndicatorTest {

    @Test
    void completedLiveSanitizedCloneIsReady() {
        RuntimeSafetyState state = mock(RuntimeSafetyState.class);
        when(state.currentCloneVerification()).thenReturn(safe("sanitized"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "preproduction");

        var health = new RuntimeSafetyHealthIndicator(state, environment).health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("cloneKind", "sanitized");
    }

    @Test
    void revokedExpiredOrSealMismatchedCloneIsNotReady() {
        RuntimeSafetyState state = mock(RuntimeSafetyState.class);
        when(state.currentCloneVerification()).thenReturn(
                new RuntimeSafetyState.CloneVerification(
                        "sanitized", false, false, false, "", false, ""));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "preproduction");

        var health = new RuntimeSafetyHealthIndicator(state, environment).health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry(
                "reason", "preproduction_clone_safety_unverified");
    }

    @Test
    void exactCompatibilityRequiresACompletedLiveExactClone() {
        RuntimeSafetyState state = mock(RuntimeSafetyState.class);
        when(state.currentCloneVerification()).thenReturn(safe("sanitized"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "preproduction")
                .withProperty("app.exact-compatibility.enabled", "true");

        assertThat(new RuntimeSafetyHealthIndicator(state, environment)
                .health().getStatus().getCode()).isEqualTo("DOWN");
    }

    @Test
    void e2eMutationReadinessRequiresTheIndependentBeforeSeal() {
        RuntimeSafetyState state = mock(RuntimeSafetyState.class);
        when(state.currentCloneVerification()).thenReturn(
                new RuntimeSafetyState.CloneVerification(
                        "sanitized", true, true, true, "fingerprint", false, ""));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "preproduction")
                .withProperty("app.staging.e2e-mutation-enabled", "true");

        assertThat(new RuntimeSafetyHealthIndicator(state, environment)
                .health().getStatus().getCode()).isEqualTo("DOWN");
    }

    @Test
    void emailValidationReadinessAlsoRequiresTheIndependentBeforeSeal() {
        RuntimeSafetyState state = mock(RuntimeSafetyState.class);
        when(state.currentCloneVerification()).thenReturn(
                new RuntimeSafetyState.CloneVerification(
                        "sanitized", true, true, true, "fingerprint", false, ""));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "preproduction")
                .withProperty("app.staging.email-validation-enabled", "true");

        assertThat(new RuntimeSafetyHealthIndicator(state, environment)
                .health().getStatus().getCode()).isEqualTo("DOWN");
    }

    @Test
    void nonPreproductionRuntimeDoesNotRequireACloneSentinel() {
        RuntimeSafetyState state = mock(RuntimeSafetyState.class);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "production");

        var health = new RuntimeSafetyHealthIndicator(state, environment).health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        verify(state, never()).currentCloneVerification();
    }

    private static RuntimeSafetyState.CloneVerification safe(String cloneKind) {
        return new RuntimeSafetyState.CloneVerification(
                cloneKind, true, true, true, "fingerprint", true, "e2e-fingerprint");
    }
}
