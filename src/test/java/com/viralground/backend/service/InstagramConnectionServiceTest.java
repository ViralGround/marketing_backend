package com.viralground.backend.service;

import com.viralground.backend.dto.creator.ConnectTokenResponse;
import com.viralground.backend.dto.creator.InstagramConnectionResponse;
import com.viralground.backend.entity.ConnectionStatus;
import com.viralground.backend.entity.CreatorInstagramConnection;
import com.viralground.backend.entity.CreatorProfile;
import com.viralground.backend.entity.Member;
import com.viralground.backend.instagram.InstagramConnectionProvider;
import com.viralground.backend.instagram.InstagramConnectionProvider.ConnectToken;
import com.viralground.backend.repository.CreatorInstagramConnectionRepository;
import com.viralground.backend.repository.CreatorProfileRepository;
import com.viralground.backend.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InstagramConnectionServiceTest {

    private final CreatorInstagramConnectionRepository repository =
            mock(CreatorInstagramConnectionRepository.class);
    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final CreatorProfileRepository creatorProfileRepository = mock(CreatorProfileRepository.class);
    private final InstagramConnectionProvider provider = mock(InstagramConnectionProvider.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private final LocalDateTime fixedNow = LocalDateTime.of(2026, 6, 10, 12, 0);
    private final Clock clock = Clock.fixed(
            fixedNow.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

    private final InstagramConnectionService service = new InstagramConnectionService(
            repository, memberRepository, creatorProfileRepository, provider, eventPublisher, clock);

    @Test
    void 연결_토큰을_발급하고_PENDING_연결을_생성하며_userId_를_저장한다() {
        // given — 기존 연결 없음, 크리에이터명 조회됨
        when(repository.findByCreatorId(1)).thenReturn(Optional.empty());
        when(memberRepository.findById(1)).thenReturn(
                Optional.of(Member.builder().id(1).name("크리에이터김").build()));
        when(provider.createConnectToken(eq(1), eq("크리에이터김"), isNull()))
                .thenReturn(new ConnectToken("sdk-token-1", "user-uuid-1", "sandbox"));

        // when
        ConnectTokenResponse res = service.getConnectToken(1);

        // then — provider 위임 + PENDING upsert + userId 저장
        assertThat(res.token()).isEqualTo("sdk-token-1");
        assertThat(res.userId()).isEqualTo("user-uuid-1");
        assertThat(res.environment()).isEqualTo("sandbox");
        ArgumentCaptor<CreatorInstagramConnection> captor =
                ArgumentCaptor.forClass(CreatorInstagramConnection.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getCreatorId()).isEqualTo(1);
        assertThat(captor.getValue().getStatus()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(captor.getValue().getProviderUserId()).isEqualTo("user-uuid-1");
    }

    @Test
    void 토큰_재발급시_기존_연결과_userId_를_재사용한다() {
        // given — 이미 user id 가 발급된 PENDING 연결 존재
        CreatorInstagramConnection existing = CreatorInstagramConnection.builder()
                .id(9).creatorId(1).providerUserId("user-uuid-1")
                .status(ConnectionStatus.PENDING).build();
        when(repository.findByCreatorId(1)).thenReturn(Optional.of(existing));
        when(memberRepository.findById(1)).thenReturn(
                Optional.of(Member.builder().id(1).name("크리에이터김").build()));
        when(provider.createConnectToken(eq(1), eq("크리에이터김"), eq("user-uuid-1")))
                .thenReturn(new ConnectToken("sdk-token-2", "user-uuid-1", "sandbox"));

        // when
        service.getConnectToken(1);

        // then — 새 레코드 생성이 아니라 기존 레코드 저장(id 유지) + 기존 userId 가 provider 로 전달됨
        ArgumentCaptor<CreatorInstagramConnection> captor =
                ArgumentCaptor.forClass(CreatorInstagramConnection.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(9);
        verify(provider).createConnectToken(1, "크리에이터김", "user-uuid-1");
    }

    @Test
    void 크리에이터명이_없으면_기본_external_id_명으로_대체한다() {
        // given — 멤버 레코드 조회 실패(엣지)
        when(repository.findByCreatorId(7)).thenReturn(Optional.empty());
        when(memberRepository.findById(7)).thenReturn(Optional.empty());
        when(provider.createConnectToken(eq(7), eq("creator-7"), isNull()))
                .thenReturn(new ConnectToken("sdk-token-7", "user-uuid-7", "sandbox"));

        // when
        service.getConnectToken(7);

        // then — 토큰 발급은 막히지 않고 대체 이름이 전달됨
        verify(provider).createConnectToken(7, "creator-7", null);
    }

    @Test
    void 연결_상태를_조회한다() {
        // given
        when(repository.findByCreatorId(1)).thenReturn(Optional.of(CreatorInstagramConnection.builder()
                .creatorId(1).status(ConnectionStatus.CONNECTED).igUsername("creator.ig")
                .connectedAt(fixedNow).build()));

        // when
        InstagramConnectionResponse res = service.getConnection(1);

        // then
        assertThat(res.connected()).isTrue();
        assertThat(res.status()).isEqualTo("CONNECTED");
        assertThat(res.igUsername()).isEqualTo("creator.ig");
    }

    @Test
    void 미연결이면_NONE_상태를_반환한다() {
        // given
        when(repository.findByCreatorId(1)).thenReturn(Optional.empty());

        // when
        InstagramConnectionResponse res = service.getConnection(1);

        // then
        assertThat(res.connected()).isFalse();
        assertThat(res.status()).isEqualTo("NONE");
        assertThat(res.igUsername()).isNull();
    }

    @Test
    void completeConnection_은_CONNECTED_와_connectedAt_accountId_를_설정한다() {
        // given — PENDING 연결(토큰 발급 단계에서 생성됨)
        CreatorInstagramConnection conn = CreatorInstagramConnection.builder()
                .creatorId(1).providerUserId("user-uuid-1").status(ConnectionStatus.PENDING).build();
        when(repository.findByCreatorId(1)).thenReturn(Optional.of(conn));

        // when — Connect SDK accountConnected 콜백
        InstagramConnectionResponse res = service.completeConnection(1, "acct-123", "9bb8913b-ddd9-430b-a66a-d74d846e6c66");

        // then
        assertThat(conn.getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(conn.getProviderAccountId()).isEqualTo("acct-123");
        assertThat(conn.getConnectedAt()).isEqualTo(fixedNow);
        assertThat(res.connected()).isTrue();
        verify(repository).save(conn);
    }

    @Test
    void completeConnection_프로필계정과_다른_계정이_연결되면_ERROR_로_막는다() {
        // given — 프로필엔 creator.real, 실제 연결된 계정은 다른 핸들
        CreatorInstagramConnection conn = CreatorInstagramConnection.builder()
                .creatorId(1).status(ConnectionStatus.PENDING).build();
        when(repository.findByCreatorId(1)).thenReturn(Optional.of(conn));
        when(creatorProfileRepository.findByMemberId(1)).thenReturn(
                Optional.of(CreatorProfile.builder().memberId(1).instagramId("creator.real").build()));
        when(provider.fetchAccountUsername("acct-x")).thenReturn("someone.else");

        // when
        InstagramConnectionResponse res = service.completeConnection(1, "acct-x", "wp");

        // then — 연결 차단(ERROR) + 두 핸들이 안내 메시지에 포함
        assertThat(conn.getStatus()).isEqualTo(ConnectionStatus.ERROR);
        assertThat(conn.getLastError()).contains("creator.real").contains("someone.else");
        assertThat(res.connected()).isFalse();
    }

    @Test
    void completeConnection_프로필계정과_일치하면_대소문자_at_무시하고_CONNECTED() {
        // given — 프로필 "Creator.Real", 연결 계정 "@creator.real" (대소문자·@ 차이)
        CreatorInstagramConnection conn = CreatorInstagramConnection.builder()
                .creatorId(1).status(ConnectionStatus.PENDING).build();
        when(repository.findByCreatorId(1)).thenReturn(Optional.of(conn));
        when(creatorProfileRepository.findByMemberId(1)).thenReturn(
                Optional.of(CreatorProfile.builder().memberId(1).instagramId("Creator.Real").build()));
        when(provider.fetchAccountUsername("acct-x")).thenReturn("@creator.real");

        // when
        InstagramConnectionResponse res = service.completeConnection(1, "acct-x", "wp");

        // then — 정규화 후 일치 → CONNECTED, 실제 연결 username(원본) 저장
        assertThat(conn.getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(conn.getIgUsername()).isEqualTo("@creator.real");
        assertThat(res.connected()).isTrue();
    }

    @Test
    void markConnected_는_CONNECTED_와_connectedAt_igUsername_을_설정한다() {
        // given
        CreatorInstagramConnection conn = CreatorInstagramConnection.builder()
                .creatorId(1).status(ConnectionStatus.PENDING).build();
        when(repository.findByCreatorId(1)).thenReturn(Optional.of(conn));

        // when
        service.markConnected(1, "acct-123", "creator.ig");

        // then
        assertThat(conn.getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(conn.getProviderAccountId()).isEqualTo("acct-123");
        assertThat(conn.getIgUsername()).isEqualTo("creator.ig");
        assertThat(conn.getConnectedAt()).isEqualTo(fixedNow);
        verify(repository).save(conn);
    }

    @Test
    void disconnect_는_DISCONNECTED_로_전이한다() {
        // given
        CreatorInstagramConnection conn = CreatorInstagramConnection.builder()
                .creatorId(1).status(ConnectionStatus.CONNECTED).igUsername("creator.ig").build();
        when(repository.findByCreatorId(1)).thenReturn(Optional.of(conn));

        // when
        service.disconnect(1);

        // then
        assertThat(conn.getStatus()).isEqualTo(ConnectionStatus.DISCONNECTED);
        verify(repository).save(conn);
    }

    @Test
    void getConnection_PENDING_인데_애그리게이터에_연결계정_있으면_자동복구한다() {
        // given — 콜백 누락으로 PENDING 에 멈춤, 애그리게이터엔 연결됨
        CreatorInstagramConnection conn = CreatorInstagramConnection.builder()
                .creatorId(1).providerUserId("user-1").status(ConnectionStatus.PENDING).build();
        when(repository.findByCreatorId(1)).thenReturn(Optional.of(conn));
        when(provider.findConnectedAccountId("user-1")).thenReturn(Optional.of("acct-9"));

        // when
        InstagramConnectionResponse res = service.getConnection(1);

        // then — 자동으로 CONNECTED 복구
        assertThat(conn.getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(conn.getProviderAccountId()).isEqualTo("acct-9");
        assertThat(res.connected()).isTrue();
    }

    @Test
    void getConnection_이미_CONNECTED_면_복구조회를_하지_않는다() {
        // given
        CreatorInstagramConnection conn = CreatorInstagramConnection.builder()
                .creatorId(1).providerUserId("user-1").status(ConnectionStatus.CONNECTED).build();
        when(repository.findByCreatorId(1)).thenReturn(Optional.of(conn));

        // when
        service.getConnection(1);

        // then — PENDING 이 아니므로 애그리게이터 조회를 하지 않음
        verify(provider, never()).findConnectedAccountId(anyString());
    }
}
