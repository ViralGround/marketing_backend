package com.viralground.backend.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import jakarta.persistence.LockModeType;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, Long> {

    /** 각 replica가 서로 다른 행을 선점해 중복 발송하지 않도록 PostgreSQL row lock을 사용한다. */
    @Query(value = """
            SELECT * FROM notification_outbox
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<NotificationOutbox> findDueForUpdate(@Param("now") Instant now,
                                               @Param("batchSize") int batchSize);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<NotificationOutbox> findByNotificationKindAndRecipientAndStatusOrderByCreatedAtAsc(
            String notificationKind, String recipient, NotificationOutboxStatus status);
}
