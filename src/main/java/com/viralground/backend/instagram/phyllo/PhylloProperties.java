package com.viralground.backend.instagram.phyllo;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Phyllo 애그리게이터 연동 설정. 모든 값은 {@code ${PHYLLO_*}} 환경변수 바인딩(기본 빈문자).
 * 키 발급 전에는 빈 값이며 {@code instagram.provider=mock} 기본이라 사용되지 않는다.
 *
 * @param clientId      Phyllo client id (PHYLLO_CLIENT_ID)
 * @param secret        Phyllo client secret (PHYLLO_CLIENT_SECRET)
 * @param baseUrl       Phyllo API base URL (PHYLLO_BASE_URL)
 * @param webhookSecret 연결 완료 웹훅 서명 검증 시크릿 (PHYLLO_WEBHOOK_SECRET)
 * @param environment   Connect SDK 환경: sandbox | staging | production (PHYLLO_ENVIRONMENT, 기본 sandbox)
 * @param products      SDK 토큰 발급 시 요청할 product 목록 (기본 IDENTITY, ENGAGEMENT)
 */
@ConfigurationProperties(prefix = "phyllo")
public record PhylloProperties(
        String clientId,
        String secret,
        String baseUrl,
        String webhookSecret,
        String environment,
        List<String> products) {

    public PhylloProperties {
        if (environment == null || environment.isBlank()) {
            environment = "sandbox";
        }
        if (products == null || products.isEmpty()) {
            products = List.of("IDENTITY", "ENGAGEMENT");
        }
    }
}
