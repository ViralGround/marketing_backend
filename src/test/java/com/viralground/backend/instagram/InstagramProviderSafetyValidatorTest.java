package com.viralground.backend.instagram;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstagramProviderSafetyValidatorTest {

    @Test
    void productionRejectsMockProvider() {
        InstagramProviderSafetyValidator validator =
                validator("mock", "production", "production", false);
        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INSTAGRAM_PROVIDER=meta");
    }

    @Test
    void unknownProviderAlwaysRejected() {
        InstagramProviderSafetyValidator validator =
                validator("phyllo", "development", "development", false);
        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void featureDisabledProtectedRuntimeStillRejectsMockSoRevocationWorks() {
        InstagramProviderSafetyValidator validator =
                validator("mock", "preproduction", "preproduction", false);

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INSTAGRAM_PROVIDER=meta");
    }

    @Test
    void protectedRuntimeCannotSpoofProviderEnvironment() {
        InstagramProviderSafetyValidator validator =
                validator("mock", "production", "test", false);

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly match APP_ENV");
    }

    @Test
    void exactCompatibilityAllowsOnlyNoNetworkDisabledProvider() {
        InstagramProviderSafetyValidator validator =
                validator("disabled", "preproduction", "preproduction", true);

        org.assertj.core.api.Assertions.assertThatCode(validator::afterPropertiesSet)
                .doesNotThrowAnyException();
    }

    private static InstagramProviderSafetyValidator validator(
            String provider, String appEnvironment, String providerEnvironment,
            boolean exactCompatibility) {
        return new InstagramProviderSafetyValidator(new MockEnvironment()
                .withProperty("instagram.provider", provider)
                .withProperty("app.environment", appEnvironment)
                .withProperty("instagram.environment", providerEnvironment)
                .withProperty("app.exact-compatibility.enabled",
                        Boolean.toString(exactCompatibility)));
    }
}
