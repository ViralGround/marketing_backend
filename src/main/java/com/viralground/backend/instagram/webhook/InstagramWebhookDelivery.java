package com.viralground.backend.instagram.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "instagram_webhook_deliveries",
        uniqueConstraints = @UniqueConstraint(name = "uk_instagram_webhook_event_hash", columnNames = "event_hash"))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InstagramWebhookDelivery {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_hash", nullable = false, length = 64)
    private String eventHash;

    @Column(name = "entry_count", nullable = false)
    private Integer entryCount;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;
}
