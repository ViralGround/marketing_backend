package com.viralground.backend.service;

import com.viralground.backend.dto.company.CompanyCampaignCreateRequest;
import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.CampaignStatus;
import com.viralground.backend.entity.EscrowStatus;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.repository.CampaignApplicationRepository;
import com.viralground.backend.repository.CampaignRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompanyServiceManagedBetaCampaignTest {

    @Mock CampaignRepository campaignRepository;
    @Mock CampaignApplicationRepository applicationRepository;
    @InjectMocks CompanyService companyService;

    @BeforeEach
    void managedBeta() {
        ReflectionTestUtils.setField(companyService, "paymentsFeatureEnabled", false);
        ReflectionTestUtils.setField(companyService, "uploadsFeatureEnabled", false);
    }

    @Test
    void create_uses_none_escrow_and_publish_changes_only_campaign_status() {
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(invocation -> {
            Campaign campaign = invocation.getArgument(0);
            if (campaign.getId() == null) campaign.setId(8);
            return campaign;
        });
        CompanyCampaignCreateRequest request = new CompanyCampaignCreateRequest();
        request.setTitle("관리형 베타 캠페인");
        request.setDescription("설명");
        request.setBrandName("브랜드");
        request.setRewardAmount(10_000);
        request.setMaxParticipants(2);
        request.setDeadline(LocalDateTime.now().plusDays(7));

        var created = companyService.createCampaign(50, request);

        assertThat(created.getStatus()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(created.getEscrowStatus()).isEqualTo(EscrowStatus.NONE);
        Campaign campaign = Campaign.builder()
                .id(8).createdById(50).status(CampaignStatus.DRAFT)
                .escrowStatus(EscrowStatus.NONE).deadline(LocalDateTime.now().plusDays(7)).build();
        when(campaignRepository.findById(8)).thenReturn(Optional.of(campaign));

        companyService.publishManagedBetaCampaign(8, 50);

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.OPEN);
        assertThat(campaign.getEscrowStatus()).isEqualTo(EscrowStatus.NONE);
    }

    @Test
    void publish_rejects_non_none_escrow() {
        Campaign campaign = Campaign.builder()
                .id(8).createdById(50).status(CampaignStatus.DRAFT)
                .escrowStatus(EscrowStatus.PENDING_DEPOSIT).deadline(LocalDateTime.now().plusDays(7)).build();
        when(campaignRepository.findById(8)).thenReturn(Optional.of(campaign));

        assertThatThrownBy(() -> companyService.publishManagedBetaCampaign(8, 50))
                .isInstanceOf(AppException.class);
        verify(campaignRepository, never()).save(any());
    }

    @Test
    void delete_allows_only_empty_draft_with_none_escrow() {
        Campaign campaign = Campaign.builder()
                .id(8).createdById(50).status(CampaignStatus.DRAFT)
                .escrowStatus(EscrowStatus.NONE).build();
        when(campaignRepository.findById(8)).thenReturn(Optional.of(campaign));
        when(applicationRepository.countByCampaignId(8)).thenReturn(0L);

        companyService.deleteCampaign(8, 50);

        verify(campaignRepository).delete(campaign);
    }
}
