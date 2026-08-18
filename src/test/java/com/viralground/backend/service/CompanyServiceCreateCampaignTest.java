package com.viralground.backend.service;

import com.viralground.backend.dto.company.CompanyCampaignCreateRequest;
import com.viralground.backend.dto.company.CompanyCampaignResponse;
import com.viralground.backend.dto.company.CompanyCampaignUpdateRequest;
import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.CampaignStatus;
import com.viralground.backend.entity.EscrowStatus;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.ApplicationSubmissionRepository;
import com.viralground.backend.repository.CampaignApplicationRepository;
import com.viralground.backend.repository.CampaignRepository;
import com.viralground.backend.repository.EscrowTransactionRepository;
import com.viralground.backend.repository.MemberRepository;
import com.viralground.backend.storage.FileStorage;
import com.viralground.backend.storage.UploadOwnershipService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CompanyServiceCreateCampaignTest {

    @Mock CampaignRepository campaignRepository;
    @Mock CampaignApplicationRepository applicationRepository;
    @Mock MemberRepository memberRepository;
    @Mock EscrowService escrowService;
    @Mock EscrowTransactionRepository escrowTransactionRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock ApplicationSubmissionRepository submissionRepository;
    @Mock FileStorage fileStorage;
    @Mock UploadOwnershipService uploadOwnershipService;
    @Mock com.viralground.backend.repository.CompanyProfileRepository companyProfileRepository;

    @InjectMocks
    CompanyService companyService;

    @Test
    void should_signed_url_응답_when_thumbnailFileKey_입력() {
        // given
        CompanyCampaignCreateRequest req = baseRequest();
        req.setThumbnailFileKey("thumbnails/abc.png");

        when(fileStorage.exists("thumbnails/abc.png")).thenReturn(true);
        when(fileStorage.signedDownloadUrl("thumbnails/abc.png"))
                .thenReturn("http://localhost:8080/files/thumbnails/abc.png?sig=xyz&exp=1");
        when(campaignRepository.save(any(Campaign.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // when
        CompanyCampaignResponse res = companyService.createCampaign(99, req);

        // then
        assertThat(res.getThumbnailUrl())
                .isEqualTo("http://localhost:8080/files/thumbnails/abc.png?sig=xyz&exp=1");
    }

    @Test
    void should_SUBMISSION_NOT_FOUND_when_업로드되지_않은_fileKey() {
        // given
        CompanyCampaignCreateRequest req = baseRequest();
        req.setThumbnailFileKey("thumbnails/missing.png");
        when(fileStorage.exists("thumbnails/missing.png")).thenReturn(false);

        // when & then
        assertThatThrownBy(() -> companyService.createCampaign(99, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SUBMISSION_NOT_FOUND);
    }

    @Test
    void should_thumbnailUrl_null_when_fileKey_없음() {
        // given — fileKey 없음, 응답 thumbnailUrl 도 null
        CompanyCampaignCreateRequest req = baseRequest();
        when(campaignRepository.save(any(Campaign.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // when
        CompanyCampaignResponse res = companyService.createCampaign(99, req);

        // then
        assertThat(res.getThumbnailUrl()).isNull();
    }

    @Test
    void should_다른_회사의_thumbnail이면_존재조회나_캠페인저장_전에_거부() {
        CompanyCampaignCreateRequest req = baseRequest();
        req.setThumbnailFileKey("thumbnails/other-company.png");
        doThrow(new AppException(ErrorCode.SUBMISSION_NOT_FOUND))
                .when(uploadOwnershipService)
                .requireOwnedUpload("thumbnails/other-company.png", 99);

        assertThatThrownBy(() -> companyService.createCampaign(99, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SUBMISSION_NOT_FOUND);

        verify(fileStorage, never()).exists("thumbnails/other-company.png");
        verify(campaignRepository, never()).save(any());
    }

    @Test
    void should_INVALID_CAMPAIGN_INPUT_when_보상금이_0이거나_예산이_int를_초과() {
        CompanyCampaignCreateRequest zeroReward = baseRequest();
        zeroReward.setRewardAmount(0);
        assertThatThrownBy(() -> companyService.createCampaign(99, zeroReward))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CAMPAIGN_INPUT);

        CompanyCampaignCreateRequest overflow = baseRequest();
        overflow.setRewardAmount(100_000_000);
        overflow.setMaxParticipants(10_000);
        assertThatThrownBy(() -> companyService.createCampaign(99, overflow))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CAMPAIGN_INPUT);
        verify(campaignRepository, never()).save(any());
    }

    @Test
    void should_업로드_소유권과_실제객체를_모두_확인() {
        CompanyCampaignCreateRequest req = baseRequest();
        req.setThumbnailFileKey("thumbnails/owned.png");
        when(fileStorage.exists("thumbnails/owned.png")).thenReturn(true);
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(inv -> inv.getArgument(0));

        companyService.createCampaign(99, req);

        verify(uploadOwnershipService).requireOwnedUpload("thumbnails/owned.png", 99);
        verify(fileStorage).exists("thumbnails/owned.png");
    }

    @Test
    void updateCampaign_다른_회사의_thumbnail이면_기존값을_변경하지_않음() {
        Campaign campaign = ownedCampaign();
        when(campaignRepository.findById(7)).thenReturn(Optional.of(campaign));
        doThrow(new AppException(ErrorCode.SUBMISSION_NOT_FOUND))
                .when(uploadOwnershipService)
                .requireOwnedUpload("thumbnails/other-company.png", 99);
        CompanyCampaignUpdateRequest req = new CompanyCampaignUpdateRequest();
        req.setTitle("변경되면 안 됨");
        req.setThumbnailFileKey("thumbnails/other-company.png");

        assertThatThrownBy(() -> companyService.updateCampaign(7, 99, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SUBMISSION_NOT_FOUND);

        assertThat(campaign.getTitle()).isEqualTo("기존 제목");
        assertThat(campaign.getThumbnailFileKey()).isEqualTo("thumbnails/old.png");
        verify(fileStorage, never()).exists("thumbnails/other-company.png");
        verify(campaignRepository, never()).save(any());
    }

    @Test
    void updateCampaign_예산_overflow면_기존값을_변경하지_않음() {
        Campaign campaign = ownedCampaign();
        when(campaignRepository.findById(7)).thenReturn(Optional.of(campaign));
        CompanyCampaignUpdateRequest req = new CompanyCampaignUpdateRequest();
        req.setTitle("변경되면 안 됨");
        req.setRewardAmount(100_000_000);
        req.setMaxParticipants(10_000);

        assertThatThrownBy(() -> companyService.updateCampaign(7, 99, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CAMPAIGN_INPUT);

        assertThat(campaign.getTitle()).isEqualTo("기존 제목");
        assertThat(campaign.getTotalBudget()).isEqualTo(50_000);
        verify(campaignRepository, never()).save(any());
    }

    private Campaign ownedCampaign() {
        return Campaign.builder()
                .id(7)
                .title("기존 제목")
                .createdById(99)
                .rewardAmount(10_000)
                .maxParticipants(5)
                .totalBudget(50_000)
                .thumbnailFileKey("thumbnails/old.png")
                .status(CampaignStatus.DRAFT)
                .escrowStatus(EscrowStatus.PENDING_DEPOSIT)
                .build();
    }

    private CompanyCampaignCreateRequest baseRequest() {
        CompanyCampaignCreateRequest req = new CompanyCampaignCreateRequest();
        req.setTitle("제목");
        req.setDescription("설명");
        req.setBrandName("브랜드");
        req.setRewardAmount(10_000);
        req.setMaxParticipants(5);
        return req;
    }
}
