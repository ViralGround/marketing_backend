package com.viralground.backend.instagram.oauth;

import com.viralground.backend.config.PreproductionScheduledMutationGuard;
import com.viralground.backend.instagram.InstagramIntegrationException;
import com.viralground.backend.instagram.meta.MetaInstagramProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InstagramOAuthStateStoreTest {

    private final InstagramOAuthStateRepository repository = mock(InstagramOAuthStateRepository.class);
    private final Instant now = Instant.parse("2026-08-13T02:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final MetaInstagramProperties properties = new MetaInstagramProperties(
            "app", "secret", "https://api.example/callback", "https://web.example/result",
            "key", "verify", "v25.0", null, null, null, List.of(),
            Duration.ofMinutes(10), Duration.ofSeconds(3), Duration.ofSeconds(8),
            Duration.ZERO, Duration.ofDays(7), 3, 50, 3, 14);
    private final InstagramOAuthStateStore store = new InstagramOAuthStateStore(
            repository, properties, clock, new SecureRandom(new byte[]{1, 2, 3}),
            mock(PreproductionScheduledMutationGuard.class));

    @BeforeEach
    void enableInstagram() {
        ReflectionTestUtils.setField(store, "instagramFeatureEnabled", true);
    }

    @Test
    void issueStoresOnlyHashAndInvalidatesPreviousUnusedState() {
        InstagramOAuthStateStore.IssuedState issued = store.issue(7);

        assertThat(issued.value()).hasSizeGreaterThan(40);
        verify(repository).invalidateUnusedByCreatorId(7, LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        org.mockito.ArgumentCaptor<InstagramOAuthState> captor =
                org.mockito.ArgumentCaptor.forClass(InstagramOAuthState.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStateHash()).isEqualTo(InstagramOAuthStateStore.hash(issued.value()));
        assertThat(captor.getValue().getStateHash()).doesNotContain(issued.value());
        assertThat(captor.getValue().getExpiresAt()).isEqualTo(
                LocalDateTime.ofInstant(now, ZoneOffset.UTC).plusMinutes(10));
    }

    @Test
    void claimMarksValidStateUsedExactlyOnce() {
        InstagramOAuthState state = InstagramOAuthState.builder()
                .id(11L).creatorId(7).stateHash("hash")
                .createdAt(LocalDateTime.ofInstant(now.minusSeconds(1), ZoneOffset.UTC))
                .expiresAt(LocalDateTime.ofInstant(now.plusSeconds(60), ZoneOffset.UTC)).build();
        when(repository.findByStateHashForUpdate(InstagramOAuthStateStore.hash("nonce")))
                .thenReturn(Optional.of(state));

        assertThat(store.claim("nonce"))
                .isEqualTo(new InstagramOAuthStateStore.ClaimedState(7, 11L));
        assertThat(state.getUsedAt()).isEqualTo(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        verify(repository).save(state);
    }

    @Test
    void claimRejectsExpiredOrPreviouslyUsedState() {
        InstagramOAuthState expired = InstagramOAuthState.builder()
                .creatorId(7).stateHash("hash")
                .expiresAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC)).build();
        when(repository.findByStateHashForUpdate(any())).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> store.claim("nonce"))
                .isInstanceOf(InstagramIntegrationException.class)
                .extracting("code").isEqualTo("INSTAGRAM_INVALID_STATE");
    }
}
