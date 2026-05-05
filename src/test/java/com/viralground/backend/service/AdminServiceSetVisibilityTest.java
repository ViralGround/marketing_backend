package com.viralground.backend.service;

import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.CampaignStatus;
import com.viralground.backend.entity.EscrowStatus;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminServiceSetVisibilityTest {

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

    Campaign campaign;

    @BeforeEach
    void setUp() {
        campaign = Campaign.builder()
                .id(1)
                .status(CampaignStatus.OPEN)
                .escrowStatus(EscrowStatus.FUNDED)
                .build();
    }

    @Test
    void should_hiddenAt_세팅_when_hidden_true() {
        // given
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));

        // when
        adminService.setCampaignVisibility(1, true);

        // then
        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaignRepository).save(captor.capture());
        assertThat(captor.getValue().getHiddenAt()).isNotNull();
        assertThat(captor.getValue().isHidden()).isTrue();
    }

    @Test
    void should_hiddenAt_null_when_hidden_false() {
        // given — 이미 숨겨진 캠페인을 다시 노출
        campaign.setHiddenAt(LocalDateTime.now());
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));

        // when
        adminService.setCampaignVisibility(1, false);

        // then
        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaignRepository).save(captor.capture());
        assertThat(captor.getValue().getHiddenAt()).isNull();
        assertThat(captor.getValue().isHidden()).isFalse();
    }

    @Test
    void should_CAMPAIGN_NOT_FOUND_예외_when_존재하지_않는_id() {
        // given
        when(campaignRepository.findById(99)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> adminService.setCampaignVisibility(99, true))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CAMPAIGN_NOT_FOUND);
    }
}
