package com.viralground.backend.instagram.phyllo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phyllo 플랫폼 API 호출 클라이언트. 크리에이터별 user 생성 + Connect SDK 토큰 발급을 담당한다.
 *
 * <p>인증은 Basic {@code base64(clientId:secret)}. 토큰/secret 은 절대 로깅·노출하지 않는다.
 * {@code instagram.provider=phyllo} 일 때만 빈으로 등록된다(mock 기본에서는 미생성).
 *
 * @see <a href="https://docs.getphyllo.com/">Phyllo API</a>
 */
@Component
@ConditionalOnProperty(name = "instagram.provider", havingValue = "phyllo")
public class PhylloClient {

    private final RestClient restClient;

    @Autowired
    public PhylloClient(PhylloProperties properties) {
        String basic = Base64.getEncoder().encodeToString(
                (properties.clientId() + ":" + properties.secret()).getBytes(StandardCharsets.UTF_8));
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /** 테스트용 생성자 — RestClient 를 직접 주입(MockRestServiceServer 등). */
    PhylloClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * 크리에이터에 대응하는 Phyllo user 를 생성한다.
     *
     * @param externalId 우리 쪽 외부 식별자(예: {@code creator-7}) — Phyllo user 와 1:1 매핑
     * @param name       크리에이터 표시명
     * @return 생성된 Phyllo user id(uuid)
     */
    public String createUser(String externalId, String name) {
        PhylloUser user = restClient.post()
                .uri("/v1/users")
                .body(Map.of("name", name, "external_id", externalId))
                .retrieve()
                .body(PhylloUser.class);
        if (user == null || user.id() == null || user.id().isBlank()) {
            throw new IllegalStateException("Phyllo user 생성 응답에 id 가 없습니다");
        }
        return user.id();
    }

    /**
     * 해당 Phyllo user 의 Connect SDK 토큰을 발급한다. 프론트가 이 토큰으로 Connect SDK 를 연다.
     *
     * @param phylloUserId Phyllo user id(uuid)
     * @return 단기 SDK 토큰(jwt)
     */
    public String createSdkToken(String phylloUserId, java.util.List<String> products) {
        PhylloSdkToken token = restClient.post()
                .uri("/v1/sdk-tokens")
                .body(Map.of("user_id", phylloUserId, "products", products))
                .retrieve()
                .body(PhylloSdkToken.class);
        if (token == null || token.sdkToken() == null || token.sdkToken().isBlank()) {
            throw new IllegalStateException("Phyllo SDK 토큰 응답에 sdk_token 이 없습니다");
        }
        return token.sdkToken();
    }

    /**
     * 연결된 계정의 최근 콘텐츠(릴스 포함)를 가져온다. 각 콘텐츠의 permalink·인게이지먼트를 담는다.
     * 최근 100건으로 제한한다(제출 릴스는 보통 최신). 못 찾으면 호출측(provider)에서 예외 처리.
     *
     * @param accountId 연결 계정 id(providerAccountId)
     * @return 콘텐츠 목록(없으면 빈 목록)
     */
    public List<PhylloContent> fetchContents(String accountId) {
        PhylloContentsResponse resp = restClient.get()
                .uri(uri -> uri.path("/v1/social/contents")
                        .queryParam("account_id", accountId)
                        .queryParam("limit", 100)
                        .build())
                .retrieve()
                .body(PhylloContentsResponse.class);
        return (resp == null || resp.data() == null) ? List.of() : resp.data();
    }

    /** GET /v1/social/contents 응답 래퍼(필요 필드만). */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record PhylloContentsResponse(List<PhylloContent> data) {}

    /**
     * 연결 계정의 인스타 username(핸들)을 조회한다. 프로필 일치 검증·표시용.
     *
     * @param accountId 연결 계정 id(providerAccountId)
     * @return username(없으면 platform_username, 둘 다 없으면 null)
     */
    public String fetchUsername(String accountId) {
        PhylloAccount acct = restClient.get()
                .uri("/v1/accounts/{id}", accountId)
                .retrieve()
                .body(PhylloAccount.class);
        if (acct == null) {
            return null;
        }
        return (acct.username() != null && !acct.username().isBlank())
                ? acct.username()
                : acct.platformUsername();
    }

    /** GET /v1/accounts/{id} 응답(필요 필드만). */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record PhylloAccount(
            String username,
            @com.fasterxml.jackson.annotation.JsonProperty("platform_username") String platformUsername) {}

    /**
     * 해당 user 의 연결된(CONNECTED) 계정 id 를 조회한다(콜백 누락 자동 복구용).
     *
     * @param userId Phyllo user id(providerUserId)
     * @return 연결된 계정 id(없으면 empty)
     */
    public Optional<String> findConnectedAccountId(String userId) {
        PhylloAccountList resp = restClient.get()
                .uri(uri -> uri.path("/v1/accounts").queryParam("user_id", userId).build())
                .retrieve()
                .body(PhylloAccountList.class);
        if (resp == null || resp.data() == null) {
            return Optional.empty();
        }
        return resp.data().stream()
                .filter(a -> "CONNECTED".equalsIgnoreCase(a.status()) && a.id() != null && !a.id().isBlank())
                .map(PhylloAccountListItem::id)
                .findFirst();
    }

    /** GET /v1/accounts?user_id= 응답 래퍼(필요 필드만). */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record PhylloAccountList(List<PhylloAccountListItem> data) {}

    /** 계정 목록 항목(필요 필드만). */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record PhylloAccountListItem(String id, String status) {}

    /** POST /v1/users 응답(필요 필드만). */
    private record PhylloUser(String id) {}

    /** POST /v1/sdk-tokens 응답(필요 필드만). Jackson 이 snake_case → record 매핑하도록 명시. */
    private record PhylloSdkToken(
            @com.fasterxml.jackson.annotation.JsonProperty("sdk_token") String sdkToken) {}
}
