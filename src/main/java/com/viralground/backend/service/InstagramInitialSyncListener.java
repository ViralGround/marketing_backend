package com.viralground.backend.service;

import com.viralground.backend.event.InstagramConnectedEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 연결 완료 이벤트를 받아 해당 크리에이터의 릴스를 즉시 동기화한다(대시보드 즉시 반영).
 * 연결 레코드 커밋 이후(AFTER_COMMIT) 비동기로 실행 — HTTP 응답을 막지 않고, 실패해도 연결엔 영향 없음.
 */
@Component
@RequiredArgsConstructor
public class InstagramInitialSyncListener {

    private static final Logger log = LoggerFactory.getLogger(InstagramInitialSyncListener.class);

    private final ReelMetricSyncService syncService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConnected(InstagramConnectedEvent event) {
        try {
            ReelMetricSyncService.SyncResult result = syncService.syncCreator(event.creatorId());
            log.info("event=instagram_initial_sync_completed creatorId={} synced={} failed={}",
                    event.creatorId(), result.synced(), result.failed());
        } catch (Exception e) {
            log.warn("event=instagram_initial_sync_failed creatorId={} errorType={}",
                    event.creatorId(), e.getClass().getSimpleName());
        }
    }
}
