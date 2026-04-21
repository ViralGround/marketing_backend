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
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new AppException(ErrorCode.CAMPAIGN_NOT_FOUND));
        if (campaign.getEscrowStatus() != EscrowStatus.DEPOSIT_CONFIRMING) {
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
        Campaign campaign = campaignRepository.findById(campaignId)
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

    private Campaign loadOwnedCampaign(Integer campaignId, Integer companyMemberId) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new AppException(ErrorCode.CAMPAIGN_NOT_FOUND));
        if (!campaign.getCreatedById().equals(companyMemberId)) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        return campaign;
    }
}
