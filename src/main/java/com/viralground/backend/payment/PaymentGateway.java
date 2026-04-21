package com.viralground.backend.payment;

public interface PaymentGateway {

    /**
     * 기업이 요청한 예치금 입금을 확정 처리한다.
     * Mock 구현에서는 관리자 승인을 즉시 확정으로 간주한다.
     * 실제 구현(PG 연동)에서는 결제 검증을 수행한다.
     */
    DepositResult confirmDeposit(Integer campaignId, Integer amount, String memo);

    /**
     * 예치금에서 크리에이터에게 지급 처리한다.
     * Mock 구현에서는 DB상 지급 완료로만 기록한다.
     */
    ReleaseResult release(Integer campaignId, Integer applicationId, Integer amount, String memo);

    /**
     * 캠페인 취소/미집행 예치금을 환불 처리한다.
     */
    RefundResult refund(Integer campaignId, Integer amount, String memo);

    record DepositResult(boolean success, String providerTxId) {}

    record ReleaseResult(boolean success, String providerTxId) {}

    record RefundResult(boolean success, String providerTxId) {}
}
