package com.viralground.backend.payment;

import com.viralground.backend.entity.EscrowTransaction;
import com.viralground.backend.entity.EscrowTxType;
import com.viralground.backend.repository.EscrowTransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentReconciliationServiceTest {

    @Mock EscrowTransactionRepository repository;
    @Mock PaymentGateway gateway;
    @InjectMocks PaymentReconciliationService service;

    @Test
    void reportsRemoteAmountMismatchAndUnknownTransaction() {
        LocalDateTime from = LocalDateTime.of(2026, 8, 12, 0, 0);
        LocalDateTime to = from.plusDays(1);
        EscrowTransaction mismatch = tx("tx-1", 100_000);
        EscrowTransaction unknown = tx("tx-2", 50_000);
        when(repository.findForReconciliation(from, to))
                .thenReturn(List.of(mismatch, unknown));
        when(gateway.providerName()).thenReturn("provider");
        when(gateway.lookup("tx-1")).thenReturn(new PaymentGateway.ReconciliationResult(
                PaymentGateway.RemoteStatus.SUCCEEDED, 90_000, "KRW"));
        when(gateway.lookup("tx-2")).thenReturn(PaymentGateway.ReconciliationResult.unknown());
        when(gateway.listTransactions(from, to)).thenReturn(List.of(
                new PaymentGateway.RemoteTransaction(
                        "orphan-tx", PaymentGateway.RemoteStatus.SUCCEEDED, 30_000, "KRW")));

        var report = service.reconcile(from, to);

        assertThat(report.checked()).isEqualTo(2);
        assertThat(report.issues()).extracting(PaymentReconciliationService.ReconciliationIssue::code)
                .containsExactly("AMOUNT_OR_CURRENCY_MISMATCH", "REMOTE_UNKNOWN", "REMOTE_ORPHAN");
    }

    private static EscrowTransaction tx(String providerTxId, int amount) {
        return EscrowTransaction.builder()
                .campaignId(1).type(EscrowTxType.DEPOSIT).amount(amount).currency("KRW")
                .operationId(providerTxId).idempotencyKey(providerTxId)
                .provider("provider").providerTxId(providerTxId)
                .actorType("SYSTEM").reason("reason").memo("reason")
                .balanceAfter(amount).build();
    }
}
