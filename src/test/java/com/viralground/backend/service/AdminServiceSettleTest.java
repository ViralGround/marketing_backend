package com.viralground.backend.service;

import com.viralground.backend.dto.admin.UpdateApplicationStatusRequest;
import com.viralground.backend.entity.*;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.*;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminServiceSettleTest {

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

    CampaignApplication app;
    Campaign campaign;

    @BeforeEach
    void setUp() {
        app = CampaignApplication.builder()
                .id(10)
                .campaignId(1)
                .creatorId(7)
                .status(ApplicationStatus.SUBMITTED)
                .build();
        campaign = Campaign.builder()
                .id(1)
                .rewardAmount(50_000)
                .totalBudget(100_000)
                .status(CampaignStatus.OPEN)
                .escrowStatus(EscrowStatus.FUNDED)
                .build();
    }

    @Test
    void should_INVALID_ESCROW_STATE_예외_when_SETTLED_요청_시_예치금_미확정() {
        // given
        campaign.setEscrowStatus(EscrowStatus.PENDING_DEPOSIT);
        when(applicationRepository.findById(10)).thenReturn(Optional.of(app));
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));
        UpdateApplicationStatusRequest req = new UpdateApplicationStatusRequest();
        req.setStatus(ApplicationStatus.SETTLED);

        // when & then — 예치금이 FUNDED/PARTIALLY_RELEASED 가 아니면 상태 전이 자체를 거부
        assertThatThrownBy(() -> adminService.updateApplication(10, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_ESCROW_STATE);

        // application 이 save 되지 않아야 함 (지급 없이 SETTLED 되면 안됨)
        verify(applicationRepository, never()).save(any());
        verify(escrowService, never()).release(anyInt(), anyInt(), anyInt());
    }

    @Test
    void should_escrowService_release_호출_및_SETTLED_저장_when_예치금_FUNDED() {
        // given
        when(applicationRepository.findById(10)).thenReturn(Optional.of(app));
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));
        UpdateApplicationStatusRequest req = new UpdateApplicationStatusRequest();
        req.setStatus(ApplicationStatus.SETTLED);

        // when
        adminService.updateApplication(10, req);

        // then
        verify(escrowService).release(1, 10, 50_000);
        verify(applicationRepository).save(app);
    }
}
