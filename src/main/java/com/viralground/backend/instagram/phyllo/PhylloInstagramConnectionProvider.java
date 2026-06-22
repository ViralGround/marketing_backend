package com.viralground.backend.instagram.phyllo;

import com.viralground.backend.entity.CreatorInstagramConnection;
import com.viralground.backend.instagram.InstagramConnectionProvider;
import com.viralground.backend.instagram.InstagramUrl;
import com.viralground.backend.instagram.ReelMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Phyllo 애그리게이터 기반 인스타그램 연동 구현.
 * {@code instagram.provider=phyllo} 일 때 활성화된다.
 *
 * <p>연결 토큰 발급 + 릴스 인사이트 fetch 모두 구현. fetch 는 연결 계정의 콘텐츠를 받아
 * 제출 릴스 URL 의 shortcode 로 매칭한다. <b>인게이지먼트 필드명은 Phyllo 문서 스키마 기준이며
 * 실제 연결 계정 응답으로 최종 검증이 필요하다</b>(매칭됐는데 전부 0이면 WARN 로그).
 * 동기화({@link com.viralground.backend.service.ReelMetricSyncService})가 per-reel 로 예외를 격리하므로,
 * 호출 시 throw 해도 다른 릴스 동기화는 계속된다.
 */
@Component
@ConditionalOnProperty(name = "instagram.provider", havingValue = "phyllo")
public class PhylloInstagramConnectionProvider implements InstagramConnectionProvider {

    private static final Logger log = LoggerFactory.getLogger(PhylloInstagramConnectionProvider.class);

    private final PhylloClient phylloClient;
    private final PhylloProperties properties;

    public PhylloInstagramConnectionProvider(PhylloClient phylloClient, PhylloProperties properties) {
        this.phylloClient = phylloClient;
        this.properties = properties;
    }

    @Override
    public ConnectToken createConnectToken(int creatorId, String creatorName, String existingProviderUserId) {
        String userId = (existingProviderUserId != null && !existingProviderUserId.isBlank())
                ? existingProviderUserId
                : phylloClient.createUser("creator-" + creatorId, creatorName);
        String sdkToken = phylloClient.createSdkToken(userId, properties.products());
        log.info("Phyllo connect-token 발급 성공 — creatorId={}, phylloUserId={}, env={}",
                creatorId, userId, properties.environment());
        return new ConnectToken(sdkToken, userId, properties.environment());
    }

    @Override
    public ReelMetrics fetchReelMetrics(CreatorInstagramConnection conn, String reelUrl) {
        String shortcode = InstagramUrl.shortcode(reelUrl)
                .orElseThrow(() -> new IllegalArgumentException("릴스 URL 형식을 인식할 수 없습니다: " + reelUrl));

        String accountId = conn.getProviderAccountId();
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalStateException(
                    "연결 계정 id 가 없습니다(connect-callback 미수신). creatorId=" + conn.getCreatorId());
        }

        PhylloContent match = phylloClient.fetchContents(accountId).stream()
                .filter(c -> c.url() != null
                        && InstagramUrl.shortcode(c.url()).filter(shortcode::equals).isPresent())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "연결 계정의 최근 콘텐츠에서 릴스를 찾지 못했습니다: shortcode=" + shortcode));

        PhylloEngagement e = match.engagement() != null
                ? match.engagement()
                : new PhylloEngagement(null, null, null, null);
        long views = nz(e.viewCount());
        long likes = nz(e.likeCount());
        long comments = nz(e.commentCount());
        long shares = nz(e.shareCount());

        if (views == 0 && likes == 0 && comments == 0 && shares == 0) {
            log.warn("Phyllo 인게이지먼트가 모두 0 — content.engagement 필드명 불일치 가능성. accountId={}, shortcode={}",
                    accountId, shortcode);
        } else {
            log.info("Phyllo 릴스 지표 수집 — shortcode={}, views={}, likes={}, comments={}, shares={}",
                    shortcode, views, likes, comments, shares);
        }
        // dailyViews(추이)는 스냅샷 시계열 delta 로 계산하므로 여기선 비움.
        return new ReelMetrics(views, likes, comments, shares, List.of());
    }

    @Override
    public String fetchAccountUsername(String providerAccountId) {
        String username = phylloClient.fetchUsername(providerAccountId);
        log.info("Phyllo 연결 계정 username 조회 — accountId={}, username={}", providerAccountId, username);
        return username;
    }

    @Override
    public Optional<String> findConnectedAccountId(String providerUserId) {
        return phylloClient.findConnectedAccountId(providerUserId);
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
