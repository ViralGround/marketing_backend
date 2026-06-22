package com.viralground.backend.service;

import com.viralground.backend.event.InstagramConnectedEvent;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InstagramInitialSyncListenerTest {

    private final ReelMetricSyncService syncService = mock(ReelMetricSyncService.class);
    private final InstagramInitialSyncListener listener = new InstagramInitialSyncListener(syncService);

    @Test
    void onConnected_는_해당_크리에이터를_동기화한다() {
        when(syncService.syncCreator(7)).thenReturn(new ReelMetricSyncService.SyncResult(1, 0));

        listener.onConnected(new InstagramConnectedEvent(7));

        verify(syncService).syncCreator(7);
    }

    @Test
    void onConnected_는_동기화_예외를_삼켜_연결에_영향을_주지_않는다() {
        when(syncService.syncCreator(7)).thenThrow(new RuntimeException("boom"));

        // 예외가 전파되지 않아야 한다
        listener.onConnected(new InstagramConnectedEvent(7));

        verify(syncService).syncCreator(7);
    }
}
