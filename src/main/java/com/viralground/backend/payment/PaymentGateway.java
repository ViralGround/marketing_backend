package com.viralground.backend.payment;

import java.time.LocalDateTime;
import java.util.List;

public interface PaymentGateway {

    /**
     * 로그, 원장, 대사에서 사용하는 안정적인 공급자 식별자다.
     * 상용 어댑터는 계약 시 확정한 소문자 식별자(예: provider company name)를 반환한다.
     */
    String providerName();

    /**
     * 기업이 요청한 예치금 입금을 확정 처리한다.
     * Mock 구현에서는 관리자 승인을 즉시 확정으로 간주한다.
     * 실제 구현(PG 연동)에서는 결제 검증을 수행한다.
     */
    DepositResult confirmDeposit(Integer campaignId, Integer amount, String currency,
                                 String idempotencyKey, String reason);

    /**
     * 예치금에서 크리에이터에게 지급 처리한다.
     * Mock 구현에서는 DB상 지급 완료로만 기록한다.
     */
    ReleaseResult release(Integer campaignId, Integer applicationId, Integer amount, String currency,
                          String idempotencyKey, String reason);

    /**
     * 캠페인 취소/미집행 예치금을 환불 처리한다.
     */
    RefundResult refund(Integer campaignId, Integer amount, String currency,
                        String idempotencyKey, String reason);

    /**
     * 운영 대사용 조회. 공급자가 조회를 지원하지 않거나 게이트웨이가 비활성화된 경우
     * UNKNOWN을 반환한다. 이 메서드는 거래 상태를 변경해서는 안 된다.
     */
    ReconciliationResult lookup(String providerTxId);

    /**
     * 기간 대사에서 공급자에는 있으나 내부 DB commit에 실패한 거래를 찾기 위한 목록 조회.
     * 상용 어댑터는 pagination을 끝까지 소비하거나 공급자 정산 파일 reader로 구현해야 한다.
     */
    default List<RemoteTransaction> listTransactions(LocalDateTime fromInclusive, LocalDateTime toExclusive) {
        return List.of();
    }

    record DepositResult(boolean success, String providerTxId) {}

    record ReleaseResult(boolean success, String providerTxId) {}

    record RefundResult(boolean success, String providerTxId) {}

    enum RemoteStatus { SUCCEEDED, FAILED, PENDING, UNKNOWN }

    record ReconciliationResult(RemoteStatus status, Integer amount, String currency) {
        public static ReconciliationResult unknown() {
            return new ReconciliationResult(RemoteStatus.UNKNOWN, null, null);
        }
    }

    record RemoteTransaction(String providerTxId, RemoteStatus status, Integer amount, String currency) {}
}
