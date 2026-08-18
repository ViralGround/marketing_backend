package com.viralground.backend.validation;

import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicUrlPolicyTest {

    @Test
    void normalizesOptionalWhitespaceAndKeepsAbsoluteHttpsUrl() {
        assertThat(PublicUrlPolicy.normalizeOptional(null)).isNull();
        assertThat(PublicUrlPolicy.normalizeOptional("   ")).isNull();
        assertThat(PublicUrlPolicy.normalizeOptional(
                "  https://www.viralground.kr/brands?id=10  "))
                .isEqualTo("https://www.viralground.kr/brands?id=10");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://viralground.kr", "/relative", "https://localhost/admin",
            "https://127.0.0.1/admin", "https://10.1.2.3", "https://192.168.1.2",
            "https://[::1]/", "https://user:password@viralground.kr",
            "https://single-label", "https://example.test", "https://203.0.113.1",
            "https://viral_ground.kr"
    })
    void rejectsNonPublicOrUnsafeUrls(String url) {
        assertThatThrownBy(() -> PublicUrlPolicy.normalizeOptional(url))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_PUBLIC_URL);
    }

    @Test
    void rejectsControlCharactersAndOver500Characters() {
        assertThatThrownBy(() -> PublicUrlPolicy.normalizeOptional(
                "https://viralground.kr/path\nInjected: value"))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_PUBLIC_URL);
        assertThatThrownBy(() -> PublicUrlPolicy.normalizeOptional(
                "https://viralground.kr/" + "a".repeat(480)))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_PUBLIC_URL);
    }
}
