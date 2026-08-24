package com.viralground.backend.payment;

import com.viralground.backend.entity.EscrowTransaction;
import com.viralground.backend.repository.EscrowTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 공급자 거래 조회와 내부 append-only 원장을 비교하는 수동/배치 공통 서비스.
 * 자동 실행 주기와 호출 제한은 공급자 계약 후 정해야 하므로 @Scheduled는 의도적으로 두지 않는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentReconciliationService {

    private final EscrowTransactionRepository transactionRepository;
    private final PaymentGateway paymentGateway;

    @Value("${features.payments.enabled:false}")
    private boolean paymentsFeatureEnabled = false;

    @Transactional(readOnly = true)
    public ReconciliationReport reconcile(LocalDateTime fromInclusive, LocalDateTime toExclusive) {
        if (!paymentsFeatureEnabled) {
            throw new AppException(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE);
        }
        if (fromInclusive == null || toExclusive == null || !fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("유효한 대사 기간이 필요합니다");
        }
        List<EscrowTransaction> transactions = transactionRepository
                .findForReconciliation(fromInclusive, toExclusive);
        List<ReconciliationIssue> issues = new ArrayList<>();
        Set<String> internalProviderIds = new HashSet<>();

        for (EscrowTransaction transaction : transactions) {
            if (!paymentGateway.providerName().equalsIgnoreCase(transaction.getProvider())) continue;
            internalProviderIds.add(transaction.getProviderTxId());
            PaymentGateway.ReconciliationResult remote = paymentGateway.lookup(transaction.getProviderTxId());
            if (remote.status() == PaymentGateway.RemoteStatus.UNKNOWN) {
                issues.add(issue(transaction, "REMOTE_UNKNOWN"));
            } else if (remote.status() != PaymentGateway.RemoteStatus.SUCCEEDED) {
                issues.add(issue(transaction, "REMOTE_STATUS_" + remote.status()));
            } else if (!transaction.getAmount().equals(remote.amount())
                    || !transaction.getCurrency().equalsIgnoreCase(remote.currency())) {
                issues.add(issue(transaction, "AMOUNT_OR_CURRENCY_MISMATCH"));
            }
        }

        for (PaymentGateway.RemoteTransaction remote : paymentGateway.listTransactions(fromInclusive, toExclusive)) {
            if (remote != null && remote.providerTxId() != null
                    && !internalProviderIds.contains(remote.providerTxId())) {
                issues.add(new ReconciliationIssue(null, null, remote.providerTxId(), "REMOTE_ORPHAN"));
            }
        }

        log.info("event=payment_reconciliation_completed provider={} from={} to={} checked={} issues={}",
                paymentGateway.providerName(), fromInclusive, toExclusive, transactions.size(), issues.size());
        issues.forEach(issue -> log.error(
                "event=payment_reconciliation_issue transactionId={} campaignId={} providerTxId={} code={}",
                issue.transactionId(), issue.campaignId(), issue.providerTxId(), issue.code()));
        return new ReconciliationReport(transactions.size(), List.copyOf(issues));
    }

    private static ReconciliationIssue issue(EscrowTransaction tx, String code) {
        return new ReconciliationIssue(tx.getId(), tx.getCampaignId(), tx.getProviderTxId(), code);
    }

    public record ReconciliationReport(int checked, List<ReconciliationIssue> issues) {}
    public record ReconciliationIssue(Integer transactionId, Integer campaignId,
                                      String providerTxId, String code) {}
}
