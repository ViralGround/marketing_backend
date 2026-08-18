package com.viralground.backend.instagram.webhook;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

public interface InstagramWebhookDeliveryRepository extends JpaRepository<InstagramWebhookDelivery, Long> {
    boolean existsByEventHash(String eventHash);
    long deleteByReceivedAtBefore(LocalDateTime cutoff);
}
