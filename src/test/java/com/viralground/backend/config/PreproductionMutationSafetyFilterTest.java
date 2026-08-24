package com.viralground.backend.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PreproductionMutationSafetyFilterTest {

    @Test
    void safeLiveSanitizedCloneAllowsMutation() throws Exception {
        RuntimeSafetyState state = mock(RuntimeSafetyState.class);
        when(state.currentCloneVerification()).thenReturn(safe());
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = request("POST");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(state, e2e()).doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void missingExpiredDestroyedOrSealMismatchedSentinelBlocksMutation() throws Exception {
        RuntimeSafetyState state = mock(RuntimeSafetyState.class);
        when(state.currentCloneVerification()).thenReturn(
                new RuntimeSafetyState.CloneVerification(
                        "sanitized", true, false, false, "different-fingerprint",
                        false, "different-e2e-fingerprint"));
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = request("DELETE");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(state, e2e()).doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).isEqualTo(
                "{\"code\":\"PREPRODUCTION_RUNTIME_UNSAFE\","
                        + "\"message\":\"Preproduction mutation safety verification failed.\"}");
        assertThat(response.getContentAsString()).doesNotContain("different-fingerprint");
    }

    @Test
    void readOnlyRequestDoesNotQuerySentinel() throws Exception {
        RuntimeSafetyState state = mock(RuntimeSafetyState.class);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = request("GET");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(state, preproduction()).doFilter(request, response, chain);

        verify(state, never()).currentCloneVerification();
        verify(chain).doFilter(request, response);
    }

    @Test
    void oauthCallbackGetIsTreatedAsMutationAndRequiresE2eSeal() throws Exception {
        RuntimeSafetyState state = mock(RuntimeSafetyState.class);
        when(state.currentCloneVerification()).thenReturn(
                migrationSafeWithoutE2eSeal());
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/instagram/meta/oauth/callback");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(state, e2e()).doFilter(request, response, chain);

        verify(chain, never()).doFilter(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(response.getStatus()).isEqualTo(503);
    }

    @Test
    void oauthCallbackGetIsAllowedOnlyWithLiveE2eSafetyProof() throws Exception {
        RuntimeSafetyState state = mock(RuntimeSafetyState.class);
        when(state.currentCloneVerification()).thenReturn(safe());
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/instagram/meta/oauth/callback");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(state, e2e()).doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void exactCompatibilityUsesItsReadOnlyDatabaseRoleInsteadOfMutationFilter() throws Exception {
        RuntimeSafetyState state = mock(RuntimeSafetyState.class);
        FilterChain chain = mock(FilterChain.class);
        MockEnvironment environment = preproduction()
                .withProperty("app.exact-compatibility.enabled", "true");
        MockHttpServletRequest request = request("POST");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(state, environment).doFilter(request, response, chain);

        verify(state, never()).currentCloneVerification();
        verify(chain).doFilter(request, response);
    }

    @Test
    void bothMutationModesDisabledBlocksEveryPreproductionMutation() throws Exception {
        RuntimeSafetyState state = mock(RuntimeSafetyState.class);
        when(state.currentCloneVerification()).thenReturn(safe());
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = request("POST");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(state, preproduction()).doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(503);
    }

    @Test
    void provisioningModeAllowsOnlyApprovedRoutesWithMigrationSeal() throws Exception {
        RuntimeSafetyState state = mock(RuntimeSafetyState.class);
        when(state.currentCloneVerification()).thenReturn(migrationSafeWithoutE2eSeal());
        MockEnvironment environment = preproduction().withProperty(
                "app.staging.account-provisioning-enabled", "true");
        FilterChain allowedChain = mock(FilterChain.class);
        MockHttpServletRequest allowed = new MockHttpServletRequest(
                "PATCH", "/admin/members/42/status");

        filter(state, environment).doFilter(
                allowed, new MockHttpServletResponse(), allowedChain);

        verify(allowedChain).doFilter(
                org.mockito.ArgumentMatchers.same(allowed),
                org.mockito.ArgumentMatchers.any(MockHttpServletResponse.class));

        FilterChain deniedChain = mock(FilterChain.class);
        MockHttpServletRequest denied = new MockHttpServletRequest(
                "POST", "/admin/campaigns");
        MockHttpServletResponse deniedResponse = new MockHttpServletResponse();
        filter(state, environment).doFilter(denied, deniedResponse, deniedChain);
        verify(deniedChain, never()).doFilter(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(deniedResponse.getStatus()).isEqualTo(503);
    }

    @Test
    void emailValidationAllowsOnlyExactProbePostWithLiveBeforeSeal() throws Exception {
        RuntimeSafetyState state = mock(RuntimeSafetyState.class);
        when(state.currentCloneVerification()).thenReturn(safe());
        MockEnvironment environment = preproduction().withProperty(
                "app.staging.email-validation-enabled", "true");
        FilterChain allowedChain = mock(FilterChain.class);
        MockHttpServletRequest allowed = new MockHttpServletRequest(
                "POST", "/admin/email-validation/probes");

        filter(state, environment).doFilter(
                allowed, new MockHttpServletResponse(), allowedChain);

        verify(allowedChain).doFilter(
                org.mockito.ArgumentMatchers.same(allowed),
                org.mockito.ArgumentMatchers.any(MockHttpServletResponse.class));

        for (String path : new String[]{
                "/admin/campaigns", "/admin/email-validation/probes/extra"}) {
            FilterChain deniedChain = mock(FilterChain.class);
            MockHttpServletResponse deniedResponse = new MockHttpServletResponse();
            filter(state, environment).doFilter(
                    new MockHttpServletRequest("POST", path), deniedResponse, deniedChain);
            verify(deniedChain, never()).doFilter(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
            assertThat(deniedResponse.getStatus()).isEqualTo(503);
        }
    }

    @Test
    void emailValidationProbeRejectsMissingSealAndQueryInputs() throws Exception {
        RuntimeSafetyState state = mock(RuntimeSafetyState.class);
        when(state.currentCloneVerification()).thenReturn(migrationSafeWithoutE2eSeal());
        MockEnvironment environment = preproduction().withProperty(
                "app.staging.email-validation-enabled", "true");
        FilterChain unsealedChain = mock(FilterChain.class);

        filter(state, environment).doFilter(
                new MockHttpServletRequest("POST", "/admin/email-validation/probes"),
                new MockHttpServletResponse(), unsealedChain);

        verify(unsealedChain, never()).doFilter(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        when(state.currentCloneVerification()).thenReturn(safe());
        MockHttpServletRequest queryRequest = new MockHttpServletRequest(
                "POST", "/admin/email-validation/probes");
        queryRequest.setQueryString("recipient=forbidden%40example.test");
        MockHttpServletResponse queryResponse = new MockHttpServletResponse();
        FilterChain queryChain = mock(FilterChain.class);
        filter(state, environment).doFilter(queryRequest, queryResponse, queryChain);

        verify(queryChain, never()).doFilter(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(queryResponse.getStatus()).isEqualTo(503);
    }

    @Test
    void productionRuntimeDoesNotDependOnCloneSentinel() throws Exception {
        RuntimeSafetyState state = mock(RuntimeSafetyState.class);
        FilterChain chain = mock(FilterChain.class);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "production");
        MockHttpServletRequest request = request("PATCH");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(state, environment).doFilter(request, response, chain);

        verify(state, never()).currentCloneVerification();
        verify(chain).doFilter(request, response);
    }

    private static PreproductionMutationSafetyFilter filter(
            RuntimeSafetyState state, MockEnvironment environment) {
        return new PreproductionMutationSafetyFilter(state, environment);
    }

    private static MockEnvironment preproduction() {
        return new MockEnvironment().withProperty(
                "app.environment", "preproduction");
    }

    private static MockEnvironment e2e() {
        return preproduction().withProperty(
                "app.staging.e2e-mutation-enabled", "true");
    }

    private static MockHttpServletRequest request(String method) {
        return new MockHttpServletRequest(method, "/auth/login");
    }

    private static RuntimeSafetyState.CloneVerification safe() {
        return new RuntimeSafetyState.CloneVerification(
                "sanitized", true, true, true, "fingerprint",
                true, "e2e-fingerprint");
    }

    private static RuntimeSafetyState.CloneVerification migrationSafeWithoutE2eSeal() {
        return new RuntimeSafetyState.CloneVerification(
                "sanitized", true, true, true, "fingerprint", false, "");
    }
}
