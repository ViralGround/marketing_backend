package com.viralground.backend.service;

import com.viralground.backend.entity.*;
import com.viralground.backend.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminServiceGetPendingEscrowTest {

    @Mock MemberRepository memberRepository;
    @Mock CreatorProfileRepository profileRepository;
    @Mock CompanyProfileRepository companyProfileRepository;
    @Mock CampaignRepository campaignRepository;
    @Mock CampaignApplicationRepository applicationRepository;
    @Mock EscrowTransactionRepository escrowTransactionRepository;
    @Mock EmailService emailService;
    @Mock EscrowService escrowService;
    @Mock ApplicationSubmissionRepository submissionRepository;
    @Mock ApplicationEventPublisher eventPublisher;

    @InjectMocks
    AdminService adminService;

    private Campaign campaign(Integer id, EscrowStatus status, LocalDateTime depositAt) {
        return Campaign.builder()
                .id(id)
                .title("캠페인 " + id)
                .brandName("브랜드 " + id)
                .createdById(100 + id)
                .totalBudget(100_000)
                .rewardAmount(10_000)
                .maxParticipants(10)
                .status(CampaignStatus.DRAFT)
                .escrowStatus(status)
                .depositRequestedAt(depositAt)
                .build();
    }

    @Test
    void PENDING_DEPOSIT_과_DEPOSIT_CONFIRMING_상태_캠페인을_모두_반환한다() {
        // given — 기업이 아직 계좌이체 완료를 누르지 않은 PENDING_DEPOSIT 캠페인도 관리자 페이지에 보여야 한다.
        Campaign pending = campaign(1, EscrowStatus.PENDING_DEPOSIT, null);
        Campaign confirming = campaign(2, EscrowStatus.DEPOSIT_CONFIRMING, LocalDateTime.now());
        when(campaignRepository.findByEscrowStatusInOrderByDepositRequestedAtAscCreatedAtAsc(
                List.of(EscrowStatus.PENDING_DEPOSIT, EscrowStatus.DEPOSIT_CONFIRMING)))
                .thenReturn(List.of(confirming, pending));
        when(companyProfileRepository.findByMemberIdIn(anyCollection()))
                .thenReturn(List.of());

        // when
        List<Map<String, Object>> result = adminService.getPendingEscrowCampaigns();

        // then
        assertThat(result).hasSize(2);
        assertThat(result).extracting(m -> m.get("id")).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void 상태_별_escrowStatus_필드를_포함한다() {
        // given — 프론트가 PENDING_DEPOSIT 항목에 대해 "기업 입금 신청 전" 뱃지를 보여주려면
        //        백엔드 응답에 escrowStatus 가 있어야 한다.
        Campaign pending = campaign(1, EscrowStatus.PENDING_DEPOSIT, null);
        Campaign confirming = campaign(2, EscrowStatus.DEPOSIT_CONFIRMING, LocalDateTime.now());
        when(campaignRepository.findByEscrowStatusInOrderByDepositRequestedAtAscCreatedAtAsc(anyCollection()))
                .thenReturn(List.of(pending, confirming));
        when(companyProfileRepository.findByMemberIdIn(anyCollection()))
                .thenReturn(List.of());

        // when
        List<Map<String, Object>> result = adminService.getPendingEscrowCampaigns();

        // then
        Map<String, Object> pendingRow = result.stream()
                .filter(r -> r.get("id").equals(1)).findFirst().orElseThrow();
        Map<String, Object> confirmingRow = result.stream()
                .filter(r -> r.get("id").equals(2)).findFirst().orElseThrow();

        assertThat(pendingRow).containsEntry("escrowStatus", "PENDING_DEPOSIT");
        assertThat(confirmingRow).containsEntry("escrowStatus", "DEPOSIT_CONFIRMING");
    }

    @Test
    void 회사명_조회는_findByMemberIdIn_한_번으로_끝난다() {
        // given — N+1 회귀 방지. 캠페인 N개여도 회사명 IN 쿼리는 1회.
        Campaign c1 = campaign(1, EscrowStatus.PENDING_DEPOSIT, null);
        Campaign c2 = campaign(2, EscrowStatus.PENDING_DEPOSIT, null);
        Campaign c3 = campaign(3, EscrowStatus.DEPOSIT_CONFIRMING, LocalDateTime.now());
        when(campaignRepository.findByEscrowStatusInOrderByDepositRequestedAtAscCreatedAtAsc(anyCollection()))
                .thenReturn(List.of(c1, c2, c3));
        when(companyProfileRepository.findByMemberIdIn(anyCollection())).thenReturn(List.of());

        // when
        adminService.getPendingEscrowCampaigns();

        // then
        org.mockito.Mockito.verify(companyProfileRepository, org.mockito.Mockito.times(1))
                .findByMemberIdIn(anyCollection());
        org.mockito.Mockito.verify(companyProfileRepository, org.mockito.Mockito.never())
                .findByMemberId(org.mockito.ArgumentMatchers.anyInt());
    }
}
