package com.viralground.backend.service;

import com.viralground.backend.dto.creator.InstagramAuthorizationResponse;
import com.viralground.backend.entity.ConnectionStatus;
import com.viralground.backend.entity.CreatorInstagramConnection;
import com.viralground.backend.entity.CreatorProfile;
import com.viralground.backend.instagram.InstagramConnectionProvider;
import com.viralground.backend.instagram.InstagramIntegrationException;
import com.viralground.backend.instagram.oauth.InstagramOAuthStateStore;
import com.viralground.backend.repository.CreatorInstagramConnectionRepository;
import com.viralground.backend.repository.CreatorProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InstagramConnectionServiceTest {

    private final CreatorInstagramConnectionRepository repository = mock(CreatorInstagramConnectionRepository.class);
    private final CreatorProfileRepository profiles = mock(CreatorProfileRepository.class);
    private final InstagramConnectionProvider provider = mock(InstagramConnectionProvider.class);
    private final InstagramOAuthStateStore states = mock(InstagramOAuthStateStore.class);
    private final InstagramConnectionFailureRecorder failureRecorder = mock(InstagramConnectionFailureRecorder.class);
    private final InstagramAuthorizationPersistenceCoordinator authorizationCoordinator =
            mock(InstagramAuthorizationPersistenceCoordinator.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final Instant now = Instant.parse("2026-08-13T02:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final InstagramConnectionService service = new InstagramConnectionService(
            repository, profiles, provider, states, failureRecorder,
            authorizationCoordinator, events, clock, "meta");

    @BeforeEach
    void profile() {
        ReflectionTestUtils.setField(service, "instagramFeatureEnabled", true);
        when(profiles.findByMemberId(7)).thenReturn(Optional.of(
                CreatorProfile.builder().memberId(7).instagramId("@Viral.Creator").build()));
        when(authorizationCoordinator.apply(any(), any())).thenReturn(
                InstagramAuthorizationPersistenceCoordinator.ApplyOutcome.APPLIED);
    }

    @Test
    void authorize_issuesMemberBoundStateAndDoesNotExposeItSeparately() {
        LocalDateTime expires = LocalDateTime.ofInstant(now.plusSeconds(600), ZoneOffset.UTC);
        when(states.issue(7)).thenReturn(new InstagramOAuthStateStore.IssuedState("nonce", expires));
        when(repository.findByCreatorIdForUpdate(7)).thenReturn(Optional.empty());
        when(provider.buildAuthorizationUrl("nonce", "@Viral.Creator")).thenReturn("https://oauth.example/authorize");

        InstagramAuthorizationResponse response = service.beginAuthorization(7);

        assertThat(response.authorizationUrl()).isEqualTo("https://oauth.example/authorize");
        assertThat(response.expiresAt()).isEqualTo(now.plusSeconds(600));
        ArgumentCaptor<CreatorInstagramConnection> saved = ArgumentCaptor.forClass(CreatorInstagramConnection.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getCreatorId()).isEqualTo(7);
        assertThat(saved.getValue().getStatus()).isEqualTo(ConnectionStatus.PENDING);
    }

    @Test
    void authorizeFailsBeforeStateWhenProfileHandleIsMissing() {
        when(profiles.findByMemberId(7)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.beginAuthorization(7))
                .isInstanceOf(InstagramIntegrationException.class)
                .extracting("code").isEqualTo("INSTAGRAM_PROFILE_HANDLE_REQUIRED");
        verify(states, never()).issue(7);
    }

    @Test
    void callbackConsumesStateAndStoresOnlyEncryptedLongLivedToken() {
        InstagramOAuthStateStore.ClaimedState claimed =
                new InstagramOAuthStateStore.ClaimedState(7, 11L);
        when(states.claim("nonce")).thenReturn(claimed);
        InstagramConnectionProvider.AuthorizationResult authorization =
                new InstagramConnectionProvider.AuthorizationResult(
                        "178900", "viral.creator", "long-lived-secret", now.plusSeconds(5_000_000));
        when(provider.exchangeAuthorizationCode("one-time-code")).thenReturn(authorization);

        service.completeAuthorization("nonce", "one-time-code");

        verify(states).claim("nonce");
        verify(authorizationCoordinator).apply(claimed, authorization);
    }

    @Test
    void callbackFailsClosedOnUsernameMismatchAndRevokesFreshAuthorization() {
        when(states.claim("nonce")).thenReturn(
                new InstagramOAuthStateStore.ClaimedState(7, 11L));
        InstagramConnectionProvider.AuthorizationResult authorization =
                new InstagramConnectionProvider.AuthorizationResult(
                        "178900", "someone.else", "secret", now.plusSeconds(5_000_000));
        when(provider.exchangeAuthorizationCode("code")).thenReturn(authorization);
        when(authorizationCoordinator.apply(any(), any())).thenReturn(
                InstagramAuthorizationPersistenceCoordinator.ApplyOutcome.ACCOUNT_MISMATCH);

        assertThatThrownBy(() -> service.completeAuthorization("nonce", "code"))
                .isInstanceOf(InstagramIntegrationException.class)
                .extracting("code").isEqualTo("INSTAGRAM_ACCOUNT_MISMATCH");

        verify(provider).revoke(authorization);
        verify(failureRecorder).record(
                new InstagramOAuthStateStore.ClaimedState(7, 11L),
                "프로필에 등록한 Instagram 계정으로 다시 연결해 주세요");
    }

    @Test
    void disconnectRevokesUpstreamBeforeDeletingLocalSecrets() {
        CreatorInstagramConnection connection = CreatorInstagramConnection.builder()
                .creatorId(7).providerAccountId("178900").providerUserId("178900")
                .encryptedAccessToken("v1:ciphertext").igUsername("viral.creator")
                .status(ConnectionStatus.CONNECTED).connectedAt(LocalDateTime.now(clock)).build();
        when(repository.findByCreatorIdForUpdate(7)).thenReturn(Optional.of(connection));

        service.disconnect(7);

        verify(states).invalidateUnusedForCreator(7);
        verify(provider).revoke(connection);
        assertThat(connection.getEncryptedAccessToken()).isNull();
        assertThat(connection.getProviderAccountId()).isNull();
        assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.DISCONNECTED);
    }

    @Test
    void disconnectKeepsLocalConnectionWhenUpstreamRevokeFails() {
        CreatorInstagramConnection connection = CreatorInstagramConnection.builder()
                .creatorId(7).providerAccountId("178900").encryptedAccessToken("v1:ciphertext")
                .status(ConnectionStatus.CONNECTED).build();
        when(repository.findByCreatorIdForUpdate(7)).thenReturn(Optional.of(connection));
        org.mockito.Mockito.doThrow(new RuntimeException("upstream"))
                .when(provider).revoke(connection);

        assertThatThrownBy(() -> service.disconnect(7)).isInstanceOf(RuntimeException.class);
        assertThat(connection.getEncryptedAccessToken()).isEqualTo("v1:ciphertext");
        assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
    }

    @Test
    void disconnectRemainsAvailableAfterFeatureKillSwitch() {
        ReflectionTestUtils.setField(service, "instagramFeatureEnabled", false);
        CreatorInstagramConnection connection = CreatorInstagramConnection.builder()
                .creatorId(7).providerAccountId("178900")
                .encryptedAccessToken("v1:ciphertext")
                .status(ConnectionStatus.CONNECTED).build();
        when(repository.findByCreatorIdForUpdate(7)).thenReturn(Optional.of(connection));

        service.disconnect(7);

        verify(provider).revoke(connection);
        assertThat(connection.getEncryptedAccessToken()).isNull();
        assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.DISCONNECTED);
    }
}
