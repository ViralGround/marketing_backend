package com.viralground.backend.service;

import com.viralground.backend.entity.ConnectionStatus;
import com.viralground.backend.entity.CreatorInstagramConnection;
import com.viralground.backend.instagram.oauth.InstagramOAuthStateStore;
import com.viralground.backend.repository.CreatorInstagramConnectionRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InstagramConnectionFailureRecorderTest {

    private final CreatorInstagramConnectionRepository repository =
            mock(CreatorInstagramConnectionRepository.class);
    private final InstagramOAuthStateStore states = mock(InstagramOAuthStateStore.class);
    private final InstagramConnectionFailureRecorder recorder =
            new InstagramConnectionFailureRecorder(repository, states, "meta");
    private final InstagramOAuthStateStore.ClaimedState claimed =
            new InstagramOAuthStateStore.ClaimedState(7, 11L);

    @Test
    void disconnectWinsAndLateFailureCannotRestoreAnErrorState() {
        CreatorInstagramConnection disconnected = CreatorInstagramConnection.builder()
                .creatorId(7).status(ConnectionStatus.DISCONNECTED).build();
        when(repository.findByCreatorIdForUpdate(7)).thenReturn(Optional.of(disconnected));

        recorder.record(claimed, "safe failure");

        assertThat(disconnected.getStatus()).isEqualTo(ConnectionStatus.DISCONNECTED);
        verify(repository, never()).save(disconnected);
    }

    @Test
    void newerAttemptWinsAndLateFailureCannotClobberPendingState() {
        CreatorInstagramConnection pending = CreatorInstagramConnection.builder()
                .creatorId(7).status(ConnectionStatus.PENDING).build();
        when(repository.findByCreatorIdForUpdate(7)).thenReturn(Optional.of(pending));
        when(states.isLatestAttempt(claimed)).thenReturn(false);

        recorder.record(claimed, "safe failure");

        assertThat(pending.getStatus()).isEqualTo(ConnectionStatus.PENDING);
        verify(repository, never()).save(pending);
    }

    @Test
    void latestPendingAttemptAloneMayRecordFailure() {
        CreatorInstagramConnection pending = CreatorInstagramConnection.builder()
                .creatorId(7).status(ConnectionStatus.PENDING).build();
        when(repository.findByCreatorIdForUpdate(7)).thenReturn(Optional.of(pending));
        when(states.isLatestAttempt(claimed)).thenReturn(true);

        recorder.record(claimed, "safe failure");

        assertThat(pending.getStatus()).isEqualTo(ConnectionStatus.ERROR);
        assertThat(pending.getLastError()).isEqualTo("safe failure");
        verify(repository).save(pending);
    }
}
