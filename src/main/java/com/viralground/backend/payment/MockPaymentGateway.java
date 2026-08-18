package com.viralground.backend.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile({"development", "dev", "test"})
@ConditionalOnProperty(name = "payments.gateway", havingValue = "mock")
@Slf4j
public class MockPaymentGateway implements PaymentGateway {

    private final Map<String, ReconciliationResult> transactions = new ConcurrentHashMap<>();

    @Override
    public String providerName() {
        return "mock";
    }

    @Override
    public DepositResult confirmDeposit(Integer campaignId, Integer amount, String currency,
                                        String idempotencyKey, String reason) {
        String txId = "MOCK-DEP-" + UUID.randomUUID();
        transactions.put(txId, new ReconciliationResult(RemoteStatus.SUCCEEDED, amount, currency));
        log.warn("event=payment_mock_deposit campaignId={} amount={} currency={} idempotencyKey={} providerTxId={}",
                campaignId, amount, currency, idempotencyKey, txId);
        return new DepositResult(true, txId);
    }

    @Override
    public ReleaseResult release(Integer campaignId, Integer applicationId, Integer amount, String currency,
                                 String idempotencyKey, String reason) {
        String txId = "MOCK-REL-" + UUID.randomUUID();
        transactions.put(txId, new ReconciliationResult(RemoteStatus.SUCCEEDED, amount, currency));
        log.warn("event=payment_mock_release campaignId={} applicationId={} amount={} currency={} idempotencyKey={} providerTxId={}",
                campaignId, applicationId, amount, currency, idempotencyKey, txId);
        return new ReleaseResult(true, txId);
    }

    @Override
    public RefundResult refund(Integer campaignId, Integer amount, String currency,
                               String idempotencyKey, String reason) {
        String txId = "MOCK-REF-" + UUID.randomUUID();
        transactions.put(txId, new ReconciliationResult(RemoteStatus.SUCCEEDED, amount, currency));
        log.warn("event=payment_mock_refund campaignId={} amount={} currency={} idempotencyKey={} providerTxId={}",
                campaignId, amount, currency, idempotencyKey, txId);
        return new RefundResult(true, txId);
    }

    @Override
    public ReconciliationResult lookup(String providerTxId) {
        return transactions.getOrDefault(providerTxId, ReconciliationResult.unknown());
    }
}
