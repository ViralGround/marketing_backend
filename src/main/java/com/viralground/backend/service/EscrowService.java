package com.viralground.backend.service;

import com.viralground.backend.entity.*;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.payment.EscrowStateMachine;
import com.viralground.backend.payment.PaymentActor;
import com.viralground.backend.payment.PaymentGateway;
import com.viralground.backend.repository.CampaignRepository;
import com.viralground.backend.repository.CompanyProfileRepository;
import com.viralground.backend.repository.EscrowTransactionRepository;
import com.viralground.backend.repository.MemberRepository;
import com.viralground.backend.repository.PaymentLedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 결제 공급자 호출, append-only 거래 원장, 이중 분개, 캠페인 상태 전이를 하나의 경계에서 처리한다.
 * 공급자 호출 성공 여부를 확인하기 전에는 어떠한 FUNDED/RELEASED/REFUNDED 상태도 만들지 않는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EscrowService {

    private static final String CURRENCY = "KRW";

    private final CampaignRepository campaignRepository;
    private final EscrowTransactionRepository escrowTransactionRepository;
    private final PaymentLedgerEntryRepository ledgerEntryRepository;
    private final CompanyProfileRepository companyProfileRepository;
    private final MemberRepository memberRepository;
    private final PaymentGateway paymentGateway;
    private final EmailService emailService;

    /** 기업이 예치금 입금을 신청한다. PENDING_DEPOSIT → DEPOSIT_CONFIRMING. */
    @Transactional
    public void requestDeposit(Integer campaignId, Integer companyMemberId) {
        Campaign campaign = lockedCampaign(campaignId);
        requireOwner(campaign, companyMemberId);
        if ("disabled".equals(normalizedProvider())) {
            log.error("event=payment_blocked operation=REQUEST_DEPOSIT campaignId={} actorType=COMPANY actorId={} reason=gateway_disabled",
                    campaignId, companyMemberId);
            throw new AppException(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE);
        }
        EscrowStateMachine.require(campaign.getEscrowStatus(), EscrowStatus.DEPOSIT_CONFIRMING);
        campaign.setEscrowStatus(EscrowStatus.DEPOSIT_CONFIRMING);
        campaign.setDepositRequestedAt(LocalDateTime.now());
        campaignRepository.save(campaign);
        log.info("event=escrow_state_changed operation=REQUEST_DEPOSIT campaignId={} actorType=COMPANY actorId={} from={} to={}",
                campaignId, companyMemberId, EscrowStatus.PENDING_DEPOSIT, EscrowStatus.DEPOSIT_CONFIRMING);

        companyProfileRepository.findByMemberId(companyMemberId).ifPresent(profile ->
                emailService.notifyAdminsOfEscrowDepositRequest(
                        campaign.getTitle(), profile.getCompanyName(), campaign.getTotalBudget()));
    }

    /** 하위 호환 시스템 호출. 관리자 HTTP 경로는 actor/reason을 명시하는 overload를 사용한다. */
    @Transactional
    public void confirmDeposit(Integer campaignId) {
        confirmDeposit(campaignId, PaymentActor.system(), "입금 확인", defaultKey("deposit", campaignId, null));
    }

    @Transactional
    public EscrowTransaction confirmDeposit(Integer campaignId, PaymentActor actor,
                                             String reason, String idempotencyKey) {
        Campaign campaign = lockedCampaign(campaignId);
        if (campaign.getEscrowStatus() != EscrowStatus.DEPOSIT_CONFIRMING
                && campaign.getEscrowStatus() != EscrowStatus.FUNDED) {
            throw new AppException(ErrorCode.INVALID_ESCROW_STATE);
        }
        return processDeposit(campaign, actor, reason, idempotencyKey);
    }

    /** 기본 상태에서도 관리자 확인을 허용하되 실제 게이트웨이 검증을 절대 우회하지 않는다. */
    @Transactional
    public void forceConfirmDeposit(Integer campaignId) {
        forceConfirmDeposit(campaignId, PaymentActor.system(), "관리자 강제 입금 확인",
                defaultKey("deposit", campaignId, null));
    }

    @Transactional
    public EscrowTransaction forceConfirmDeposit(Integer campaignId, PaymentActor actor,
                                                  String reason, String idempotencyKey) {
        Campaign campaign = lockedCampaign(campaignId);
        EscrowStatus current = campaign.getEscrowStatus();
        if (current != EscrowStatus.NONE
                && current != EscrowStatus.PENDING_DEPOSIT
                && current != EscrowStatus.DEPOSIT_CONFIRMING
                && current != EscrowStatus.FUNDED) {
            throw new AppException(ErrorCode.INVALID_ESCROW_STATE);
        }
        return processDeposit(campaign, actor, reason, idempotencyKey);
    }

    private EscrowTransaction processDeposit(Campaign campaign, PaymentActor actor,
                                             String reasonValue, String keyValue) {
        int amount = requirePositive(campaign.getTotalBudget());
        PaymentActor safeActor = actor == null ? PaymentActor.system() : actor;
        String reason = normalizeReason(reasonValue);
        String key = normalizeKey(keyValue, "deposit", campaign.getId(), null);

        EscrowTransaction existing = findIdempotent(key, campaign.getId(), null, EscrowTxType.DEPOSIT, amount);
        if (existing != null) {
            applyFundedState(campaign);
            log.info("event=payment_idempotent_replay operation=DEPOSIT campaignId={} idempotencyKey={} transactionId={}",
                    campaign.getId(), key, existing.getId());
            return existing;
        }
        if (escrowTransactionRepository.existsByCampaignIdAndType(campaign.getId(), EscrowTxType.DEPOSIT)) {
            throw new AppException(ErrorCode.PAYMENT_IDEMPOTENCY_CONFLICT);
        }

        long started = System.nanoTime();
        PaymentGateway.DepositResult result = paymentGateway.confirmDeposit(
                campaign.getId(), amount, CURRENCY, key, reason);
        requireGatewaySuccess("DEPOSIT", campaign.getId(), null, key,
                result != null && result.success(), result == null ? null : result.providerTxId(), started);

        EscrowTransaction transaction = appendTransaction(
                campaign.getId(), null, EscrowTxType.DEPOSIT, amount, amount,
                safeActor, reason, key, result.providerTxId(),
                PaymentLedgerAccount.GATEWAY_CLEARING, PaymentLedgerAccount.ESCROW_AVAILABLE);
        applyFundedState(campaign);

        memberRepository.findById(campaign.getCreatedById()).ifPresent(owner ->
                companyProfileRepository.findByMemberId(owner.getId()).ifPresent(profile ->
                        emailService.notifyCompanyOfEscrowFunded(
                                owner.getEmail(), profile.getCompanyName(), campaign.getTitle())));
        return transaction;
    }

    private void applyFundedState(Campaign campaign) {
        if (campaign.getEscrowStatus() != EscrowStatus.FUNDED) {
            EscrowStateMachine.require(campaign.getEscrowStatus(), EscrowStatus.FUNDED);
            campaign.setEscrowStatus(EscrowStatus.FUNDED);
            campaign.setFundedAt(LocalDateTime.now());
            if (campaign.getStatus() == CampaignStatus.DRAFT) campaign.setStatus(CampaignStatus.OPEN);
            campaignRepository.save(campaign);
        }
    }

    @Transactional
    public void rejectDeposit(Integer campaignId) {
        rejectDeposit(campaignId, PaymentActor.system(), "입금 확인 반려");
    }

    @Transactional
    public void rejectDeposit(Integer campaignId, PaymentActor actor, String reasonValue) {
        Campaign campaign = lockedCampaign(campaignId);
        if (campaign.getEscrowStatus() != EscrowStatus.DEPOSIT_CONFIRMING) {
            throw new AppException(ErrorCode.INVALID_ESCROW_STATE);
        }
        String reason = normalizeReason(reasonValue);
        EscrowStateMachine.require(campaign.getEscrowStatus(), EscrowStatus.PENDING_DEPOSIT);
        campaign.setEscrowStatus(EscrowStatus.PENDING_DEPOSIT);
        campaign.setDepositRequestedAt(null);
        campaignRepository.save(campaign);
        PaymentActor safeActor = actor == null ? PaymentActor.system() : actor;
        log.info("event=escrow_state_changed operation=REJECT_DEPOSIT campaignId={} actorType={} actorId={} from={} to={} reasonRecorded={}",
                campaignId, safeActor.type(), safeActor.memberId(), EscrowStatus.DEPOSIT_CONFIRMING,
                EscrowStatus.PENDING_DEPOSIT, !reason.isBlank());
    }

    @Transactional
    public void release(Integer campaignId, Integer applicationId, Integer amount) {
        release(campaignId, applicationId, amount, PaymentActor.system(), "크리에이터 지급",
                defaultKey("release", campaignId, applicationId));
    }

    @Transactional
    public EscrowTransaction release(Integer campaignId, Integer applicationId, Integer amountValue,
                                     PaymentActor actor, String reasonValue, String keyValue) {
        int amount = requirePositive(amountValue);
        Campaign campaign = lockedCampaign(campaignId);
        if (applicationId == null) throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);

        PaymentActor safeActor = actor == null ? PaymentActor.system() : actor;
        String reason = normalizeReason(reasonValue);
        String key = normalizeKey(keyValue, "release", campaignId, applicationId);
        EscrowTransaction existing = findIdempotent(
                key, campaignId, applicationId, EscrowTxType.RELEASE, amount);
        if (existing != null) {
            log.info("event=payment_idempotent_replay operation=RELEASE campaignId={} applicationId={} idempotencyKey={} transactionId={}",
                    campaignId, applicationId, key, existing.getId());
            return existing;
        }
        if (campaign.getEscrowStatus() != EscrowStatus.FUNDED
                && campaign.getEscrowStatus() != EscrowStatus.PARTIALLY_RELEASED) {
            throw new AppException(ErrorCode.INVALID_ESCROW_STATE);
        }
        if (escrowTransactionRepository.findFirstByCampaignIdAndApplicationIdAndType(
                campaignId, applicationId, EscrowTxType.RELEASE).isPresent()) {
            throw new AppException(ErrorCode.PAYMENT_IDEMPOTENCY_CONFLICT);
        }

        int remaining = currentBalance(campaignId);
        if (remaining < amount) throw new AppException(ErrorCode.INSUFFICIENT_ESCROW_BALANCE);

        long started = System.nanoTime();
        PaymentGateway.ReleaseResult result = paymentGateway.release(
                campaignId, applicationId, amount, CURRENCY, key, reason);
        requireGatewaySuccess("RELEASE", campaignId, applicationId, key,
                result != null && result.success(), result == null ? null : result.providerTxId(), started);

        int balanceAfter = remaining - amount;
        EscrowTransaction transaction = appendTransaction(
                campaignId, applicationId, EscrowTxType.RELEASE, amount, balanceAfter,
                safeActor, reason, key, result.providerTxId(),
                PaymentLedgerAccount.ESCROW_AVAILABLE, PaymentLedgerAccount.CREATOR_PAYOUT);
        EscrowStatus next = balanceAfter == 0 ? EscrowStatus.RELEASED : EscrowStatus.PARTIALLY_RELEASED;
        EscrowStateMachine.require(campaign.getEscrowStatus(), next);
        campaign.setEscrowStatus(next);
        campaignRepository.save(campaign);
        return transaction;
    }

    @Transactional
    public void refund(Integer campaignId) {
        refund(campaignId, PaymentActor.system(), "캠페인 취소 환불",
                defaultKey("refund", campaignId, null));
    }

    @Transactional
    public EscrowTransaction refund(Integer campaignId, PaymentActor actor,
                                    String reasonValue, String keyValue) {
        Campaign campaign = lockedCampaign(campaignId);
        PaymentActor safeActor = actor == null ? PaymentActor.system() : actor;
        String reason = normalizeReason(reasonValue);
        String key = normalizeKey(keyValue, "refund", campaignId, null);
        EscrowTransaction existing = escrowTransactionRepository.findByIdempotencyKey(key).orElse(null);
        if (existing != null) {
            if (!Objects.equals(existing.getCampaignId(), campaignId)
                    || existing.getApplicationId() != null
                    || existing.getType() != EscrowTxType.REFUND) {
                throw new AppException(ErrorCode.PAYMENT_IDEMPOTENCY_CONFLICT);
            }
            log.info("event=payment_idempotent_replay operation=REFUND campaignId={} idempotencyKey={} transactionId={}",
                    campaignId, key, existing.getId());
            return existing;
        }
        if (campaign.getEscrowStatus() != EscrowStatus.FUNDED
                && campaign.getEscrowStatus() != EscrowStatus.PARTIALLY_RELEASED) {
            throw new AppException(ErrorCode.INVALID_ESCROW_STATE);
        }

        int remaining = currentBalance(campaignId);
        if (remaining <= 0) throw new AppException(ErrorCode.INSUFFICIENT_ESCROW_BALANCE);

        long started = System.nanoTime();
        PaymentGateway.RefundResult result = paymentGateway.refund(
                campaignId, remaining, CURRENCY, key, reason);
        requireGatewaySuccess("REFUND", campaignId, null, key,
                result != null && result.success(), result == null ? null : result.providerTxId(), started);

        EscrowTransaction transaction = appendTransaction(
                campaignId, null, EscrowTxType.REFUND, remaining, 0,
                safeActor, reason, key, result.providerTxId(),
                PaymentLedgerAccount.ESCROW_AVAILABLE, PaymentLedgerAccount.CUSTOMER_REFUND);
        EscrowStateMachine.require(campaign.getEscrowStatus(), EscrowStatus.REFUNDED);
        campaign.setEscrowStatus(EscrowStatus.REFUNDED);
        campaign.setRefundedAt(LocalDateTime.now());
        campaignRepository.save(campaign);
        return transaction;
    }

    private EscrowTransaction appendTransaction(
            Integer campaignId, Integer applicationId, EscrowTxType type,
            int amount, int balanceAfter, PaymentActor actor, String reason,
            String idempotencyKey, String providerTxId,
            PaymentLedgerAccount debitAccount, PaymentLedgerAccount creditAccount) {
        String provider = normalizedProvider();
        String operationId = UUID.randomUUID().toString();
        EscrowTransaction transaction = escrowTransactionRepository.saveAndFlush(EscrowTransaction.builder()
                .campaignId(campaignId)
                .applicationId(applicationId)
                .type(type)
                .amount(amount)
                .currency(CURRENCY)
                .operationId(operationId)
                .idempotencyKey(idempotencyKey)
                .provider(provider)
                .providerTxId(providerTxId)
                .actorMemberId(actor.memberId())
                .actorType(actor.type())
                .reason(reason)
                .memo(reason)
                .balanceAfter(balanceAfter)
                .build());

        ledgerEntryRepository.saveAll(List.of(
                ledgerEntry(transaction, operationId, debitAccount, PaymentLedgerDirection.DEBIT),
                ledgerEntry(transaction, operationId, creditAccount, PaymentLedgerDirection.CREDIT)));
        log.info("event=payment_committed operation={} campaignId={} applicationId={} amount={} currency={} balanceAfter={} actorType={} actorId={} provider={} providerTxId={} idempotencyKey={} transactionId={} operationId={}",
                type, campaignId, applicationId, amount, CURRENCY, balanceAfter, actor.type(), actor.memberId(),
                provider, providerTxId, idempotencyKey, transaction.getId(), operationId);
        return transaction;
    }

    private PaymentLedgerEntry ledgerEntry(EscrowTransaction tx, String operationId,
                                           PaymentLedgerAccount account, PaymentLedgerDirection direction) {
        return PaymentLedgerEntry.builder()
                .escrowTransactionId(tx.getId())
                .operationId(operationId)
                .campaignId(tx.getCampaignId())
                .applicationId(tx.getApplicationId())
                .account(account)
                .direction(direction)
                .amount(tx.getAmount())
                .currency(tx.getCurrency())
                .build();
    }

    private EscrowTransaction findIdempotent(String key, Integer campaignId, Integer applicationId,
                                             EscrowTxType type, int amount) {
        return escrowTransactionRepository.findByIdempotencyKey(key)
                .map(existing -> {
                    if (!Objects.equals(existing.getCampaignId(), campaignId)
                            || !Objects.equals(existing.getApplicationId(), applicationId)
                            || existing.getType() != type
                            || !Objects.equals(existing.getAmount(), amount)) {
                        throw new AppException(ErrorCode.PAYMENT_IDEMPOTENCY_CONFLICT);
                    }
                    return existing;
                })
                .orElse(null);
    }

    private int currentBalance(Integer campaignId) {
        long deposits = escrowTransactionRepository.sumAmountByCampaignIdAndType(campaignId, EscrowTxType.DEPOSIT);
        long releases = escrowTransactionRepository.sumAmountByCampaignIdAndType(campaignId, EscrowTxType.RELEASE);
        long refunds = escrowTransactionRepository.sumAmountByCampaignIdAndType(campaignId, EscrowTxType.REFUND);
        long balance = deposits - releases - refunds;
        if (deposits <= 0 || balance < 0 || balance > Integer.MAX_VALUE) {
            log.error("event=payment_ledger_invariant_failed campaignId={} deposits={} releases={} refunds={} balance={}",
                    campaignId, deposits, releases, refunds, balance);
            throw new AppException(ErrorCode.INVALID_ESCROW_STATE);
        }
        return (int) balance;
    }

    private void requireGatewaySuccess(String operation, Integer campaignId, Integer applicationId,
                                       String key, boolean success, String providerTxId, long started) {
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        if (!success || providerTxId == null || providerTxId.isBlank()) {
            log.error("event=payment_gateway_failed operation={} campaignId={} applicationId={} provider={} idempotencyKey={} elapsedMs={}",
                    operation, campaignId, applicationId, normalizedProvider(), key, elapsedMs);
            throw new AppException("disabled".equals(normalizedProvider())
                    ? ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE : ErrorCode.PAYMENT_GATEWAY_REJECTED);
        }
        log.info("event=payment_gateway_succeeded operation={} campaignId={} applicationId={} provider={} providerTxId={} idempotencyKey={} elapsedMs={}",
                operation, campaignId, applicationId, normalizedProvider(), providerTxId, key, elapsedMs);
    }

    private String normalizedProvider() {
        String provider = paymentGateway.providerName();
        return provider == null || provider.isBlank() ? "unknown" : provider.trim().toLowerCase(Locale.ROOT);
    }

    private static int requirePositive(Integer amount) {
        if (amount == null || amount <= 0) throw new AppException(ErrorCode.INVALID_PAYMENT_AMOUNT);
        return amount;
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
            throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
        }
        return reason.trim();
    }

    private static String normalizeKey(String key, String operation,
                                       Integer campaignId, Integer applicationId) {
        String value = key == null || key.isBlank()
                ? defaultKey(operation, campaignId, applicationId)
                : key.trim();
        if (value.length() > 160) throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
        return value;
    }

    private static String defaultKey(String operation, Integer campaignId, Integer applicationId) {
        return applicationId == null
                ? operation + ":campaign:" + campaignId
                : operation + ":campaign:" + campaignId + ":application:" + applicationId;
    }

    private Campaign lockedCampaign(Integer campaignId) {
        return campaignRepository.findByIdForUpdate(campaignId)
                .orElseThrow(() -> new AppException(ErrorCode.CAMPAIGN_NOT_FOUND));
    }

    private void requireOwner(Campaign campaign, Integer companyMemberId) {
        if (!campaign.getCreatedById().equals(companyMemberId)) throw new AppException(ErrorCode.FORBIDDEN);
    }
}
