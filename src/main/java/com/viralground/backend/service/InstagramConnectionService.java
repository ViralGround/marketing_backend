package com.viralground.backend.service;

import com.viralground.backend.dto.creator.ConnectTokenResponse;
import com.viralground.backend.dto.creator.InstagramConnectionResponse;
import com.viralground.backend.entity.ConnectionStatus;
import com.viralground.backend.entity.CreatorInstagramConnection;
import com.viralground.backend.entity.CreatorProfile;
import com.viralground.backend.event.InstagramConnectedEvent;
import com.viralground.backend.instagram.InstagramConnectionProvider;
import com.viralground.backend.instagram.InstagramConnectionProvider.ConnectToken;
import com.viralground.backend.repository.CreatorInstagramConnectionRepository;
import com.viralground.backend.repository.CreatorProfileRepository;
import com.viralground.backend.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 크리에이터 인스타그램 연동 상태 관리. 연결 토큰 발급(PENDING upsert), 상태 조회,
 * 연결 완료 마킹(CONNECTED), 해제(DISCONNECTED)를 담당한다. 실제 지표 수집은
 * {@link ReelMetricSyncService} 가 별도로 수행한다.
 *
 * <p><b>계정 일치 검증:</b> 연결 완료 시 실제 연결된 계정의 username 을 조회해
 * 프로필에 등록된 인스타 아이디와 비교한다. 다르면 ERROR 로 막아 다른 계정 연동을 방지한다.
 *
 * <p><b>콜백 누락 자동 복구:</b> 연결 콜백을 놓쳐 PENDING 에 멈춘 경우, 상태 조회 시 애그리게이터에
 * 연결된 계정이 있는지 확인해 자동으로 연결을 확정한다(웹훅 없이 재조정).
 *
 * <p>연결이 CONNECTED 되면 {@link InstagramConnectedEvent} 를 발행해 연결 직후 초기 동기화를 트리거한다.
 */
@Service
@RequiredArgsConstructor
public class InstagramConnectionService {

    private static final Logger log = LoggerFactory.getLogger(InstagramConnectionService.class);

    private final CreatorInstagramConnectionRepository repository;
    private final MemberRepository memberRepository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final InstagramConnectionProvider provider;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /**
     * 연결 토큰 발급 + PENDING 연결 upsert. 동의 화면 진입 전 호출.
     *
     * <p>연결 레코드를 find-or-create 하고, 크리에이터명·기존 애그리게이터 user id 를 provider 에 넘겨
     * SDK 토큰을 받는다. 신규 발급된 user id 는 레코드에 저장해 다음 발급 때 재사용한다.
     */
    @Transactional
    public ConnectTokenResponse getConnectToken(int creatorId) {
        CreatorInstagramConnection conn = findOrCreate(creatorId);

        String creatorName = memberRepository.findById(creatorId)
                .map(m -> m.getName())
                .orElse("creator-" + creatorId);

        ConnectToken token = provider.createConnectToken(creatorId, creatorName, conn.getProviderUserId());

        conn.setProviderUserId(token.userId());
        conn.setStatus(ConnectionStatus.PENDING);
        conn.setLastError(null);
        repository.save(conn);

        return new ConnectTokenResponse(token.token(), token.userId(), token.environment());
    }

    /**
     * 현재 연동 상태. 레코드가 없으면 미연결(NONE)로 응답하되 프로필 아이디는 함께 전달(게이트용).
     *
     * <p>PENDING 이고 애그리게이터 user id 가 있으면 콜백 누락 자동 복구를 시도한다.
     */
    @Transactional
    public InstagramConnectionResponse getConnection(int creatorId) {
        CreatorInstagramConnection conn = repository.findByCreatorId(creatorId).orElse(null);
        if (conn != null
                && conn.getStatus() == ConnectionStatus.PENDING
                && conn.getProviderUserId() != null && !conn.getProviderUserId().isBlank()) {
            reconcilePending(conn);
        }
        String handle = profileHandle(creatorId);
        return (conn != null)
                ? InstagramConnectionResponse.from(conn, handle)
                : InstagramConnectionResponse.none(handle);
    }

    /**
     * 연결 완료 처리(Connect SDK 의 accountConnected 콜백). 계정 일치 검증 후 CONNECTED/ERROR.
     *
     * @param creatorId       크리에이터 id
     * @param accountId       애그리게이터가 부여한 연결 계정 id(account)
     * @param workplatformId  연결된 워크플랫폼 id(참고용 — 현재 미저장)
     */
    @Transactional
    public InstagramConnectionResponse completeConnection(int creatorId, String accountId, String workplatformId) {
        CreatorInstagramConnection conn = findOrCreate(creatorId);
        return applyConnection(conn, accountId);
    }

    /** 연결 완료 처리(mock 즉시 연결). CONNECTED + connectedAt 설정. igUsername 까지 받는다. */
    @Transactional
    public InstagramConnectionResponse markConnected(int creatorId, String providerAccountId, String igUsername) {
        CreatorInstagramConnection conn = findOrCreate(creatorId);
        conn.setProviderAccountId(providerAccountId);
        conn.setIgUsername(igUsername);
        conn.setStatus(ConnectionStatus.CONNECTED);
        conn.setConnectedAt(LocalDateTime.now(clock));
        conn.setLastError(null);
        repository.save(conn);
        eventPublisher.publishEvent(new InstagramConnectedEvent(creatorId));
        return toResponse(conn);
    }

    /** 연동 해제. DISCONNECTED 로 전이하고 동기화 대상에서 제외. 레코드가 없으면 멱등 no-op. */
    @Transactional
    public void disconnect(int creatorId) {
        repository.findByCreatorId(creatorId).ifPresent(conn -> {
            conn.setStatus(ConnectionStatus.DISCONNECTED);
            repository.save(conn);
        });
    }

    // --- helpers ---

    /**
     * 연결 적용(콜백/자동복구 공통). 실제 연결된 계정의 username 을 프로필 인스타 아이디와 비교한다:
     * <ul>
     *   <li>둘 다 있고 <b>다르면</b> → ERROR(연결 차단) + 안내 메시지.</li>
     *   <li>일치(또는 한쪽이라도 없어 검증 불가) → CONNECTED + connectedAt + 초기 동기화 이벤트.</li>
     * </ul>
     */
    private InstagramConnectionResponse applyConnection(CreatorInstagramConnection conn, String accountId) {
        conn.setProviderAccountId(accountId);
        conn.setLastError(null);

        String profileRaw = profileHandle(conn.getCreatorId());
        String connectedRaw = safeFetchUsername(accountId);
        String profileNorm = normalizeHandle(profileRaw);
        String connectedNorm = normalizeHandle(connectedRaw);

        if (profileNorm != null && connectedNorm != null && !profileNorm.equals(connectedNorm)) {
            // 프로필에 등록된 계정과 다른 계정이 연결됨 → 차단(ERROR). 자동 동기화 대상에서 제외된다.
            conn.setStatus(ConnectionStatus.ERROR);
            conn.setIgUsername(connectedRaw);
            conn.setLastError(String.format(
                    "프로필 계정(@%s)과 다른 계정(@%s)이 연결되었습니다. 프로필을 수정하거나 올바른 계정으로 다시 연결해 주세요.",
                    displayHandle(profileRaw), displayHandle(connectedRaw)));
            repository.save(conn);
            log.warn("Instagram 연결 계정 불일치 — creatorId={}, profile=@{}, connected=@{}",
                    conn.getCreatorId(), profileNorm, connectedNorm);
            return toResponse(conn);
        }

        conn.setStatus(ConnectionStatus.CONNECTED);
        conn.setIgUsername(connectedRaw); // username 조회 실패 시 null
        conn.setConnectedAt(LocalDateTime.now(clock));
        repository.save(conn);
        eventPublisher.publishEvent(new InstagramConnectedEvent(conn.getCreatorId())); // 연결 직후 초기 동기화
        return toResponse(conn);
    }

    /** 콜백 누락 자동 복구: 애그리게이터에 연결된 계정이 있으면 연결을 확정한다. 조회 실패는 무시(PENDING 유지). */
    private void reconcilePending(CreatorInstagramConnection conn) {
        try {
            Optional<String> accountId = provider.findConnectedAccountId(conn.getProviderUserId());
            if (accountId.isPresent()) {
                log.info("콜백 누락 자동 복구 — creatorId={}, accountId={}", conn.getCreatorId(), accountId.get());
                applyConnection(conn, accountId.get());
            }
        } catch (Exception ex) {
            log.warn("연결 자동 복구 조회 실패 — creatorId={}: {}", conn.getCreatorId(), ex.getMessage());
        }
    }

    private CreatorInstagramConnection findOrCreate(int creatorId) {
        return repository.findByCreatorId(creatorId)
                .orElseGet(() -> CreatorInstagramConnection.builder()
                        .creatorId(creatorId)
                        .provider("PHYLLO")
                        .build());
    }

    /** 프로필에 등록된 인스타 아이디(비어 있으면 null). */
    private String profileHandle(int creatorId) {
        return creatorProfileRepository.findByMemberId(creatorId)
                .map(CreatorProfile::getInstagramId)
                .filter(s -> s != null && !s.isBlank())
                .orElse(null);
    }

    /** 연결된 계정 username 조회(실패 시 null → 일치 검증 생략하고 연결 허용). */
    private String safeFetchUsername(String accountId) {
        try {
            return provider.fetchAccountUsername(accountId);
        } catch (Exception ex) {
            log.warn("Instagram 연결 계정 username 조회 실패 — accountId={}, 일치 검증 생략", accountId, ex);
            return null;
        }
    }

    private InstagramConnectionResponse toResponse(CreatorInstagramConnection conn) {
        return InstagramConnectionResponse.from(conn, profileHandle(conn.getCreatorId()));
    }

    /** 비교용 정규화: 앞 @ 제거 + trim + 소문자. 빈 값/널은 null. */
    private static String normalizeHandle(String s) {
        if (s == null) {
            return null;
        }
        String h = s.trim().replaceFirst("^@", "").toLowerCase();
        return h.isBlank() ? null : h;
    }

    /** 표시용: 앞 @ 제거 + trim(대소문자 보존). */
    private static String displayHandle(String s) {
        return s == null ? "" : s.trim().replaceFirst("^@", "");
    }
}
