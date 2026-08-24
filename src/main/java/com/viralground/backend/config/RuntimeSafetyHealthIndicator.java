package com.viralground.backend.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Readiness proof for a normal preproduction runtime.
 *
 * <p>The check is intentionally live: every invocation re-reads the release
 * sentinel, its evidence seal and the V1 baseline through {@link RuntimeSafetyState}.
 * Configuration values or a successful startup are not durable proof that the
 * clone remains approved.</p>
 */
@Component("runtimeSafety")
public final class RuntimeSafetyHealthIndicator implements HealthIndicator {
    private final RuntimeSafetyState runtimeSafetyState;
    private final Environment environment;

    public RuntimeSafetyHealthIndicator(
            RuntimeSafetyState runtimeSafetyState,
            Environment environment) {
        this.runtimeSafetyState = runtimeSafetyState;
        this.environment = environment;
    }

    @Override
    public Health health() {
        if (!isPreproduction()
                || environment.getProperty(
                "app.migration-runner.enabled", Boolean.class, false)) {
            return Health.up().withDetail("runtimeSafety", "not_applicable").build();
        }

        RuntimeSafetyState.CloneVerification verification =
                runtimeSafetyState.currentCloneVerification();
        String expectedKind = environment.getProperty(
                "app.exact-compatibility.enabled", Boolean.class, false)
                ? "exact" : "sanitized";
        boolean e2eMutationEnabled = environment.getProperty(
                "app.staging.e2e-mutation-enabled", Boolean.class, false);
        boolean emailValidationEnabled = environment.getProperty(
                "app.staging.email-validation-enabled", Boolean.class, false);
        boolean safe = expectedKind.equals(verification.cloneKind())
                && verification.releaseIdMatched()
                && verification.migrationEvidenceComplete()
                && verification.evidenceSealMatched()
                && (!(e2eMutationEnabled || emailValidationEnabled)
                || verification.e2eBeforeEvidenceSealMatched());
        if (!safe) {
            return Health.down()
                    .withDetail("reason", "preproduction_clone_safety_unverified")
                    .build();
        }
        return Health.up().withDetail("cloneKind", expectedKind).build();
    }

    private boolean isPreproduction() {
        return "preproduction".equals(
                environment.getProperty("app.environment", "development"))
                || Arrays.stream(environment.getActiveProfiles())
                .anyMatch("preproduction"::equals);
    }
}
