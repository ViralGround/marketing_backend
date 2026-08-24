package com.viralground.backend.service;

import com.viralground.backend.entity.ConnectionStatus;
import com.viralground.backend.entity.CreatorInstagramConnection;
import com.viralground.backend.entity.CreatorProfile;
import com.viralground.backend.instagram.InstagramConnectionProvider;
import com.viralground.backend.instagram.InstagramTokenCipher;
import com.viralground.backend.instagram.oauth.InstagramOAuthStateStore;
import com.viralground.backend.repository.CreatorInstagramConnectionRepository;
import com.viralground.backend.repository.CreatorProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InstagramAuthorizationPersistenceCoordinatorTest {

    private final CreatorInstagramConnectionRepository repository =
            mock(CreatorInstagramConnectionRepository.class);
    private final CreatorProfileRepository profiles = mock(CreatorProfileRepository.class);
    private final InstagramOAuthStateStore states = mock(InstagramOAuthStateStore.class);
    private final InstagramTokenCipher cipher = mock(InstagramTokenCipher.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-08-22T00:00:00Z"), ZoneOffset.UTC);
    private final InstagramAuthorizationPersistenceCoordinator coordinator =
            new InstagramAuthorizationPersistenceCoordinator(
                    repository, profiles, states, cipher, events, clock, "meta");
    private final InstagramOAuthStateStore.ClaimedState claimed =
            new InstagramOAuthStateStore.ClaimedState(7, 11L);
    private final InstagramConnectionProvider.AuthorizationResult authorization =
            new InstagramConnectionProvider.AuthorizationResult(
                    "178900", "viral.creator", "plain-token",
                    Instant.parse("2026-10-22T00:00:00Z"));

    @BeforeEach
    void matchingProfile() {
        when(profiles.findByMemberId(7)).thenReturn(Optional.of(
                CreatorProfile.builder().memberId(7).instagramId("@Viral.Creator").build()));
    }

    @Test
    void disconnectedConnectionWinsAndFreshAuthorizationIsNeverPersisted() {
        CreatorInstagramConnection disconnected = CreatorInstagramConnection.builder()
                .creatorId(7).status(ConnectionStatus.DISCONNECTED).build();
        when(repository.findByCreatorIdForUpdate(7)).thenReturn(Optional.of(disconnected));

        assertThat(coordinator.apply(claimed, authorization))
                .isEqualTo(InstagramAuthorizationPersistenceCoordinator.ApplyOutcome.SUPERSEDED);

        verify(cipher, never()).encrypt(any());
        verify(repository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void newerOAuthAttemptWinsAndOlderCallbackCannotPersistItsToken() {
        CreatorInstagramConnection pending = CreatorInstagramConnection.builder()
                .creatorId(7).status(ConnectionStatus.PENDING).build();
        when(repository.findByCreatorIdForUpdate(7)).thenReturn(Optional.of(pending));
        when(states.isLatestAttempt(claimed)).thenReturn(false);

        assertThat(coordinator.apply(claimed, authorization))
                .isEqualTo(InstagramAuthorizationPersistenceCoordinator.ApplyOutcome.SUPERSEDED);

        verify(cipher, never()).encrypt(any());
        verify(repository, never()).save(any());
    }

    @Test
    void onlyLatestPendingAttemptPersistsEncryptedAuthorization() {
        CreatorInstagramConnection pending = CreatorInstagramConnection.builder()
                .creatorId(7).status(ConnectionStatus.PENDING).build();
        when(repository.findByCreatorIdForUpdate(7)).thenReturn(Optional.of(pending));
        when(states.isLatestAttempt(claimed)).thenReturn(true);
        when(repository.existsByProviderAccountIdAndCreatorIdNot("178900", 7))
                .thenReturn(false);
        when(cipher.encrypt("plain-token")).thenReturn("v1:ciphertext");

        assertThat(coordinator.apply(claimed, authorization))
                .isEqualTo(InstagramAuthorizationPersistenceCoordinator.ApplyOutcome.APPLIED);

        assertThat(pending.getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(pending.getEncryptedAccessToken()).isEqualTo("v1:ciphertext");
        verify(repository).save(pending);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(any(Object.class));
    }
}
