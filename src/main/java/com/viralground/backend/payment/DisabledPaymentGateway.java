package com.viralground.backend.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 상용 결제 제공자가 선택·설정되기 전의 유일한 기본 구현이다.
 * 어떠한 금전 작업도 성공시키지 않는 fail-closed 게이트웨이이며,
 * 프로덕션에서 mock으로 조용히 대체되는 일을 막는다.
 */
@Component
@ConditionalOnProperty(name = "payments.gateway", havingValue = "disabled", matchIfMissing = true)
@Slf4j
public class DisabledPaymentGateway implements PaymentGateway {

    @Override
    public String providerName() {
        return "disabled";
    }

    @Override
    public DepositResult confirmDeposit(Integer campaignId, Integer amount, String currency,
                                        String idempotencyKey, String reason) {
        log.error("event=payment_blocked operation=DEPOSIT campaignId={} amount={} currency={} idempotencyKey={} reason=gateway_disabled",
                campaignId, amount, currency, idempotencyKey);
        return new DepositResult(false, null);
    }

    @Override
    public ReleaseResult release(Integer campaignId, Integer applicationId, Integer amount, String currency,
                                 String idempotencyKey, String reason) {
        log.error("event=payment_blocked operation=RELEASE campaignId={} applicationId={} amount={} currency={} idempotencyKey={} reason=gateway_disabled",
                campaignId, applicationId, amount, currency, idempotencyKey);
        return new ReleaseResult(false, null);
    }

    @Override
    public RefundResult refund(Integer campaignId, Integer amount, String currency,
                               String idempotencyKey, String reason) {
        log.error("event=payment_blocked operation=REFUND campaignId={} amount={} currency={} idempotencyKey={} reason=gateway_disabled",
                campaignId, amount, currency, idempotencyKey);
        return new RefundResult(false, null);
    }

    @Override
    public ReconciliationResult lookup(String providerTxId) {
        return ReconciliationResult.unknown();
    }
}
