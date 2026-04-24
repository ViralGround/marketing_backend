package com.viralground.backend.service;

import com.viralground.backend.entity.*;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.payment.PaymentGateway;
import com.viralground.backend.repository.CampaignRepository;
import com.viralground.backend.repository.CompanyProfileRepository;
import com.viralground.backend.repository.EscrowTransactionRepository;
import com.viralground.backend.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class EscrowService {

    private final CampaignRepository campaignRepository;
    private final EscrowTransactionRepository escrowTransactionRepository;
    private final CompanyProfileRepository companyProfileRepository;
    private final MemberRepository memberRepository;
    private final PaymentGateway paymentGateway;
    private final EmailService emailService;

    /**
     * 기업이 예치금 입금을 신청한다. (Mock: 계좌이체 완료 버튼 클릭)
     * PENDING_DEPOSIT → DEPOSIT_CONFIRMING
     */
    @Transactional
    public void requestDeposit(Integer campaignId, Integer companyMemberId) {
        Campaign campaign = loadOwnedCampaign(campaignId, companyMemberId);
        if (campaign.getEscrowStatus() != EscrowStatus.PENDING_DEPOSIT) {
            throw new AppException(ErrorCode.INVALID_ESCROW_STATE);
        }
        campaign.setEscrowStatus(EscrowStatus.DEPOSIT_CONFIRMING);
        campaign.setDepositRequestedAt(LocalDateTime.now());
        campaignRepository.save(campaign);

        companyProfileRepository.findByMemberId(companyMemberId).ifPresent(profile ->
                emailService.notifyAdminsOfEscrowDepositRequest(
                        campaign.getTitle(),
                        profile.getCompanyName(),
                        campaign.getTotalBudget()
                )
        );
    }

    /**
     * 관리자가 입금을 확인한다. (Mock: PG 확인 생략, 즉시 FUNDED)
     * DEPOSIT_CONFIRMING → FUNDED, 캠페인 status DRAFT → OPEN
     */
    @Transactional
    public void confirmDeposit(Integer campaignId) {
        // 캠페인 행 쓰기 락으로 동시 confirmDeposit 를 직렬화. 동일 캠페인에
        // 이미 DEPOSIT 트랜잭션이 기록됐다면 중복 호출로 간주하고 거부.
        Campaign campaign = campaignRepository.findByIdForUpdate(campaignId)
                .orElseThrow(() -> new AppException(ErrorCode.CAMPAIGN_NOT_FOUND));
        if (campaign.getEscrowStatus() != EscrowStatus.DEPOSIT_CONFIRMING) {
            throw new AppException(ErrorCode.INVALID_ESCROW_STATE);
        }
        if (escrowTransactionRepository.existsByCampaignIdAndType(campaignId, EscrowTxType.DEPOSIT)) {
            throw new AppException(ErrorCode.INVALID_ESCROW_STATE);
        }

        PaymentGateway.DepositResult result = paymentGateway.confirmDeposit(
                campaign.getId(), campaign.getTotalBudget(), "escrow deposit");

        escrowTransactionRepository.save(EscrowTransaction.builder()
                .campaignId(campaign.getId())
                .type(EscrowTxType.DEPOSIT)
                .amount(campaign.getTotalBudget())
                .memo(result.providerTxId())
                .build());

        campaign.setEscrowStatus(EscrowStatus.FUNDED);
        campaign.setFundedAt(LocalDateTime.now());
        if (campaign.getStatus() == CampaignStatus.DRAFT) {
            campaign.setStatus(CampaignStatus.OPEN);
        }
        campaignRepository.save(campaign);

        memberRepository.findById(campaign.getCreatedById()).ifPresent(owner ->
                companyProfileRepository.findByMemberId(owner.getId()).ifPresent(profile ->
                        emailService.notifyCompanyOfEscrowFunded(
                                owner.getEmail(), profile.getCompanyName(), campaign.getTitle())
                )
        );
    }

    /**
     * 관리자가 예치금을 임의로 완료 처리한다.
     * NONE / PENDING_DEPOSIT / DEPOSIT_CONFIRMING → FUNDED, 캠페인 status DRAFT → OPEN.
     * 기업이 "계좌이체 완료"를 누르지 않은 상태에서도 오프라인 합의 등으로 즉시 완료할 때 사용.
     */
    @Transactional
    public void forceConfirmDeposit(Integer campaignId) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new AppException(ErrorCode.CAMPAIGN_NOT_FOUND));

        EscrowStatus current = campaign.getEscrowStatus();
        if (current != EscrowStatus.NONE
                && current != EscrowStatus.PENDING_DEPOSIT
                && current != EscrowStatus.DEPOSIT_CONFIRMING) {
            throw new AppException(ErrorCode.INVALID_ESCROW_STATE);
        }

        PaymentGateway.DepositResult result = paymentGateway.confirmDeposit(
                campaign.getId(), campaign.getTotalBudget(), "admin force deposit");

        escrowTransactionRepository.save(EscrowTransaction.builder()
                .campaignId(campaign.getId())
                .type(EscrowTxType.DEPOSIT)
                .amount(campaign.getTotalBudget())
                .memo(result.providerTxId())
                .build());

        campaign.setEscrowStatus(EscrowStatus.FUNDED);
        campaign.setFundedAt(LocalDateTime.now());
        if (campaign.getStatus() == CampaignStatus.DRAFT) {
            campaign.setStatus(CampaignStatus.OPEN);
        }
        campaignRepository.save(campaign);

        memberRepository.findById(campaign.getCreatedById()).ifPresent(owner -> {
            if (owner.getRole() == Role.COMPANY) {
                companyProfileRepository.findByMemberId(owner.getId()).ifPresent(profile ->
                        emailService.notifyCompanyOfEscrowFunded(
                                owner.getEmail(), profile.getCompanyName(), campaign.getTitle())
                );
            }
        });
    }

    /**
     * 관리자가 입금 확인을 반려한다. DEPOSIT_CONFIRMING → PENDING_DEPOSIT
     */
    @Transactional
    public void rejectDeposit(Integer campaignId) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new AppException(ErrorCode.CAMPAIGN_NOT_FOUND));
        if (campaign.getEscrowStatus() != EscrowStatus.DEPOSIT_CONFIRMING) {
            throw new AppException(ErrorCode.INVALID_ESCROW_STATE);
        }
        campaign.setEscrowStatus(EscrowStatus.PENDING_DEPOSIT);
        campaign.setDepositRequestedAt(null);
        campaignRepository.save(campaign);
    }

    /**
     * 지원자에게 예치금에서 보상금을 지급한다.
     * Application SETTLED 단계에서 호출된다.
     */
    @Transactional
    public void release(Integer campaignId, Integer applicationId, Integer amount) {
        // 잔액 계산 + RELEASE 기록을 같은 임계구역에서 처리해야 동시 지급 시
        // 같은 잔액을 두 번 참조하는 초과 지급을 막을 수 있다.
        Campaign campaign = campaignRepository.findByIdForUpdate(campaignId)
                .orElseThrow(() -> new AppException(ErrorCode.CAMPAIGN_NOT_FOUND));

        if (campaign.getEscrowStatus() != EscrowStatus.FUNDED
                && campaign.getEscrowStatus() != EscrowStatus.PARTIALLY_RELEASED) {
            throw new AppException(ErrorCode.INVALID_ESCROW_STATE);
        }

        int released = escrowTransactionRepository.findByCampaignIdOrderByCreatedAtDesc(campaignId)
                .stream()
                .filter(tx -> tx.getType() == EscrowTxType.RELEASE)
                .mapToInt(EscrowTransaction::getAmount)
                .sum();
        int remaining = campaign.getTotalBudget() - released;
        if (remaining < amount) {
            throw new AppException(ErrorCode.INSUFFICIENT_ESCROW_BALANCE);
        }

        PaymentGateway.ReleaseResult result = paymentGateway.release(
                campaignId, applicationId, amount, "creator payout");

        escrowTransactionRepository.save(EscrowTransaction.builder()
                .campaignId(campaignId)
                .applicationId(applicationId)
                .type(EscrowTxType.RELEASE)
                .amount(amount)
                .memo(result.providerTxId())
                .build());

        campaign.setEscrowStatus(EscrowStatus.PARTIALLY_RELEASED);
        campaignRepository.save(campaign);
    }

    /**
     * 기업이 캠페인을 취소하고 예치금을 환불한다. FUNDED 상태에서 남은 예치금 전액을 REFUND로 기록.
     */
    @Transactional
    public void refund(Integer campaignId) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new AppException(ErrorCode.CAMPAIGN_NOT_FOUND));

        EscrowStatus current = campaign.getEscrowStatus();
        if (current != EscrowStatus.FUNDED && current != EscrowStatus.PARTIALLY_RELEASED) {
            return;
        }

        int released = escrowTransactionRepository.findByCampaignIdOrderByCreatedAtDesc(campaignId)
                .stream()
                .filter(tx -> tx.getType() == EscrowTxType.RELEASE)
                .mapToInt(EscrowTransaction::getAmount)
                .sum();
        int remaining = campaign.getTotalBudget() - released;
        if (remaining <= 0) {
            campaign.setEscrowStatus(EscrowStatus.REFUNDED);
            campaign.setRefundedAt(LocalDateTime.now());
            campaignRepository.save(campaign);
            return;
        }

        PaymentGateway.RefundResult result = paymentGateway.refund(campaignId, remaining, "campaign cancelled");

        escrowTransactionRepository.save(EscrowTransaction.builder()
                .campaignId(campaignId)
                .type(EscrowTxType.REFUND)
                .amount(remaining)
                .memo(result.providerTxId())
                .build());

        campaign.setEscrowStatus(EscrowStatus.REFUNDED);
        campaign.setRefundedAt(LocalDateTime.now());
        campaignRepository.save(campaign);
    }

    private Campaign loadOwnedCampaign(Integer campaignId, Integer companyMemberId) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new AppException(ErrorCode.CAMPAIGN_NOT_FOUND));
        if (!campaign.getCreatedById().equals(companyMemberId)) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        return campaign;
    }
}
