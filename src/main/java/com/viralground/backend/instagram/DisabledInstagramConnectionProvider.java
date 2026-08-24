package com.viralground.backend.instagram;

import com.viralground.backend.entity.CreatorInstagramConnection;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** No-network provider for guarded migration and exact-clone compatibility processes. */
@Component
@ConditionalOnProperty(name = "instagram.provider", havingValue = "disabled")
public final class DisabledInstagramConnectionProvider implements InstagramConnectionProvider {

    private static InstagramIntegrationException disabled() {
        return new InstagramIntegrationException("INSTAGRAM_PROVIDER_DISABLED",
                "Instagram provider is disabled in this process", HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Override
    public String buildAuthorizationUrl(String state, String profileHandle) {
        throw disabled();
    }

    @Override
    public AuthorizationResult exchangeAuthorizationCode(String code) {
        throw disabled();
    }

    @Override
    public void revoke(AuthorizationResult authorization) {
        // Guarded clone processes must have no external egress.
    }

    @Override
    public ReelMetrics fetchReelMetrics(CreatorInstagramConnection connection, String reelUrl) {
        throw disabled();
    }

    @Override
    public void revoke(CreatorInstagramConnection connection) {
        // Guarded clone processes must have no external egress.
    }
}
