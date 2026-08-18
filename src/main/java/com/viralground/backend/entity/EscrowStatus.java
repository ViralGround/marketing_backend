package com.viralground.backend.entity;

public enum EscrowStatus {
    NONE,
    PENDING_DEPOSIT,
    DEPOSIT_CONFIRMING,
    FUNDED,
    PARTIALLY_RELEASED,
    RELEASED,
    REFUNDED
}
