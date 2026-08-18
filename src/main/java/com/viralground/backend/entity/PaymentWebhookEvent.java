package com.viralground.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 서명이 검증된 공급자 webhook 수신 원장. 원문 payload 대신 SHA-256만 보관해
 * 결제·개인정보 원문이 로그/DB에 중복 저장되는 것을 피한다.
 */
@Entity
@Table(name = "payment_webhook_events",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_payment_webhook_provider_event", columnNames = {"provider", "provider_event_id"}),
        indexes = @Index(name = "idx_payment_webhook_received_at", columnList = "received_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class PaymentWebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50, updatable = false)
    private String provider;

    @Column(name = "provider_event_id", nullable = false, length = 200, updatable = false)
    private String providerEventId;

    @Column(name = "event_type", nullable = false, length = 120, updatable = false)
    private String eventType;

    @Column(name = "provider_object_id", length = 200, updatable = false)
    private String providerObjectId;

    @Column(name = "payload_sha256", nullable = false, length = 64, updatable = false)
    private String payloadSha256;

    @Column(name = "provider_occurred_at", updatable = false)
    private LocalDateTime providerOccurredAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    @PrePersist
    void onCreate() {
        if (receivedAt == null) receivedAt = LocalDateTime.now();
    }
}
