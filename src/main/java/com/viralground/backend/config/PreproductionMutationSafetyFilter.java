package com.viralground.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fails closed before a normal preproduction request can mutate the clone when
 * its live release sentinel, baseline or sealed evidence stops matching.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public final class PreproductionMutationSafetyFilter extends OncePerRequestFilter {
    private static final Set<String> MUTATING_METHODS =
            Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> MUTATING_GET_PATHS = Set.of(
            "/instagram/meta/oauth/callback");
    private static final Set<String> PROVISIONING_POST_PATHS = Set.of(
            "/auth/email/request-code",
            "/auth/email/verify-code",
            "/auth/signup",
            "/auth/signup/company",
            "/auth/login",
            "/auth/logout");
    private static final Pattern PROVISIONING_APPROVAL_PATH = Pattern.compile(
            "^/admin/members/[1-9][0-9]*/status$");
    private static final String EMAIL_VALIDATION_PROBE_PATH =
            "/admin/email-validation/probes";
    private static final byte[] UNSAFE_RESPONSE = """
            {"code":"PREPRODUCTION_RUNTIME_UNSAFE","message":"Preproduction mutation safety verification failed."}
            """.strip().getBytes(StandardCharsets.UTF_8);

    private final RuntimeSafetyState runtimeSafetyState;
    private final Environment environment;

    public PreproductionMutationSafetyFilter(
            RuntimeSafetyState runtimeSafetyState,
            Environment environment) {
        this.runtimeSafetyState = runtimeSafetyState;
        this.environment = environment;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!requiresLiveSafetyProof(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        RuntimeSafetyState.CloneVerification verification =
                runtimeSafetyState.currentCloneVerification();
        boolean migrationSafe = "sanitized".equals(verification.cloneKind())
                && verification.releaseIdMatched()
                && verification.migrationEvidenceComplete()
                && verification.evidenceSealMatched();
        boolean provisioningEnabled = environment.getProperty(
                "app.staging.account-provisioning-enabled", Boolean.class, false);
        boolean e2eEnabled = environment.getProperty(
                "app.staging.e2e-mutation-enabled", Boolean.class, false);
        boolean emailValidationEnabled = environment.getProperty(
                "app.staging.email-validation-enabled", Boolean.class, false);
        int enabledModes = (provisioningEnabled ? 1 : 0)
                + (e2eEnabled ? 1 : 0) + (emailValidationEnabled ? 1 : 0);
        boolean regularMutationSafe = enabledModes == 1 && !emailValidationEnabled
                && (e2eEnabled && verification.e2eBeforeEvidenceSealMatched()
                || provisioningEnabled && isApprovedProvisioningRoute(request));
        boolean emailProbeSafe = enabledModes == 1 && emailValidationEnabled
                && verification.e2eBeforeEvidenceSealMatched()
                && isEmailValidationProbeRoute(request);
        boolean safe = migrationSafe && (regularMutationSafe || emailProbeSafe);
        if (safe) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.getOutputStream().write(UNSAFE_RESPONSE);
    }

    private boolean requiresLiveSafetyProof(HttpServletRequest request) {
        return "preproduction".equals(
                environment.getProperty("app.environment", "development"))
                && !environment.getProperty(
                "app.migration-runner.enabled", Boolean.class, false)
                && !environment.getProperty(
                "app.exact-compatibility.enabled", Boolean.class, false)
                && (MUTATING_METHODS.contains(request.getMethod())
                || "GET".equals(request.getMethod())
                && MUTATING_GET_PATHS.contains(request.getRequestURI()));
    }

    private static boolean isApprovedProvisioningRoute(HttpServletRequest request) {
        if ("POST".equals(request.getMethod())) {
            return PROVISIONING_POST_PATHS.contains(request.getRequestURI());
        }
        return "PATCH".equals(request.getMethod())
                && PROVISIONING_APPROVAL_PATH.matcher(request.getRequestURI()).matches();
    }

    private static boolean isEmailValidationProbeRoute(HttpServletRequest request) {
        return "POST".equals(request.getMethod())
                && EMAIL_VALIDATION_PROBE_PATH.equals(request.getRequestURI())
                && request.getQueryString() == null;
    }
}
