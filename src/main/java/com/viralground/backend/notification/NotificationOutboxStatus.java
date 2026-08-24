package com.viralground.backend.notification;

public enum NotificationOutboxStatus {
    PENDING,
    SUPERSEDED,
    SENT,
    DEAD_LETTER
}
