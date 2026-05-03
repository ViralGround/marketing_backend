package com.viralground.backend.service;

import com.viralground.backend.dto.company.CompanyCampaignCreateRequest;
import com.viralground.backend.dto.company.CompanyCampaignResponse;
import com.viralground.backend.entity.Campaign;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.ApplicationSubmissionRepository;
import com.viralground.backend.repository.CampaignApplicationRepository;
import com.viralground.backend.repository.CampaignRepository;
import com.viralground.backend.repository.EscrowTransactionRepository;
import com.viralground.backend.repository.MemberRepository;
import com.viralground.backend.storage.FileStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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
