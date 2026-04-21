package com.viralground.backend.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Slf4j
public class MockPaymentGateway implements PaymentGateway {

    @Override
    public DepositResult confirmDeposit(Integer campaignId, Integer amount, String memo) {
        String txId = "MOCK-DEP-" + UUID.randomUUID();
        log.info("[MockPG] deposit confirmed campaignId={} amount={} memo={} txId={}",
                campaignId, amount, memo, txId);
        return new DepositResult(true, txId);
    }

    @Override
    public ReleaseResult release(Integer campaignId, Integer applicationId, Integer amount, String memo) {
        String txId = "MOCK-REL-" + UUID.randomUUID();
        log.info("[MockPG] release campaignId={} applicationId={} amount={} memo={} txId={}",
                campaignId, applicationId, amount, memo, txId);
        return new ReleaseResult(true, txId);
    }

    @Override
    public RefundResult refund(Integer campaignId, Integer amount, String memo) {
        String txId = "MOCK-REF-" + UUID.randomUUID();
        log.info("[MockPG] refund campaignId={} amount={} memo={} txId={}",
                campaignId, amount, memo, txId);
        return new RefundResult(true, txId);
    }
}
