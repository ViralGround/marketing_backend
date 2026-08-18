package com.viralground.backend.instagram;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class InstagramProviderSafetyValidatorTest {

    @Test
    void productionRejectsMockProvider() {
        InstagramProviderSafetyValidator validator =
                new InstagramProviderSafetyValidator("mock", "production");
        assertThatThrownBy(() -> validator.run(mock(org.springframework.boot.ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INSTAGRAM_PROVIDER=meta");
    }

    @Test
    void unknownProviderAlwaysRejected() {
        InstagramProviderSafetyValidator validator =
                new InstagramProviderSafetyValidator("phyllo", "development");
        assertThatThrownBy(() -> validator.run(mock(org.springframework.boot.ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class);
    }
}
