package com.viralground.backend.legal;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 가입 화면에 실제로 노출한 법적 문서의 불변 버전 식별자.
 *
 * <p>표시용 제목이나 "최신" 같은 가변 문자열이 아니라, 법무 검토가 끝난 문서마다
 * 새로 발급하는 식별자를 사용해야 한다. 운영 환경에서는
 * {@code ProductionSafetyValidator}가 초안/placeholder 값을 거부한다.</p>
 */
@ConfigurationProperties(prefix = "legal")
@Getter
@Setter
public class LegalDocumentProperties {

    private Documents documents = new Documents();
    private PrivacyOfficer privacyOfficer = new PrivacyOfficer();

    @Getter
    @Setter
    public static class Documents {
        private String termsVersion = "v1.0-draft";
        private String privacyVersion = "v1.0-draft";
        private String age14Version = "v1.0-draft";
        private String creatorThirdPartyVersion = "v1.0-draft";
        private String marketingVersion = "v1.0-draft";
    }

    @Getter
    @Setter
    public static class PrivacyOfficer {
        private String name = "";
        private String contact = "";
    }
}
