package com.viralground.backend.repository;

import com.viralground.backend.entity.PaymentWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentWebhookEventRepository extends JpaRepository<PaymentWebhookEvent, Long> {
    Optional<PaymentWebhookEvent> findByProviderAndProviderEventId(String provider, String providerEventId);
}
