package com.viralground.backend.entity;

/** 결제 원장의 논리 계정. 실제 회계 계정과의 매핑은 세무 정책 확정 후 구성한다. */
public enum PaymentLedgerAccount {
    GATEWAY_CLEARING,
    ESCROW_AVAILABLE,
    CREATOR_PAYOUT,
    CUSTOMER_REFUND
}
