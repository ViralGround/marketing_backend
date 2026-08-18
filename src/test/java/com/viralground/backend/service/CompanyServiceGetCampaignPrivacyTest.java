package com.viralground.backend.service;

import com.viralground.backend.dto.company.CompanyCampaignResponse;
import com.viralground.backend.entity.ApplicationStatus;
import com.viralground.backend.entity.ApplicationSubmission;
import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.CampaignApplication;
import com.viralground.backend.entity.Member;
import com.viralground.backend.entity.SubmissionReviewStatus;
import com.viralground.backend.repository.ApplicationSubmissionRepository;
import com.viralground.backend.repository.CampaignApplicationRepository;
import com.viralground.backend.repository.CampaignRepository;
import com.viralground.backend.repository.CompanyProfileRepository;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanyServiceGetCampaignPrivacyTest {
    @Mock CampaignRepository campaignRepository;
    @Mock CampaignApplicationRepository applicationRepository;
    @Mock MemberRepository memberRepository;
    @Mock EscrowService escrowService;
    @Mock EscrowTransactionRepository escrowTransactionRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock ApplicationSubmissionRepository submissionRepository;
    @Mock FileStorage fileStorage;
    @Mock UploadOwnershipService uploadOwnershipService;
    @Mock CompanyProfileRepository companyProfileRepository;

    @InjectMocks CompanyService companyService;

    @Test
    void companyResponseUsesSignedVideoUrlsAndDoesNotExposeRawKeysOrCreatorEmail() {
        Campaign campaign = Campaign.builder().id(3).createdById(50).build();
        CampaignApplication application = CampaignApplication.builder()
                .id(7).campaignId(3).creatorId(11)
                .status(ApplicationStatus.SUBMITTED)
                .videoFileKey("submissions/current.mp4")
                .build();
        ApplicationSubmission historical = ApplicationSubmission.builder()
                .id(9).applicationId(7)
                .videoFileKey("submissions/history.mp4")
                .videoContentType("video/mp4")
                .videoSizeBytes(1234L)
                .status(SubmissionReviewStatus.SUBMITTED)
                .build();
        Member creator = Member.builder()
                .id(11).name("크리에이터").email("private@example.com").build();
        when(campaignRepository.findById(3)).thenReturn(Optional.of(campaign));
        when(applicationRepository.findByCampaignIdOrderByAppliedAtDesc(3))
                .thenReturn(List.of(application));
        when(memberRepository.findAllById(List.of(11))).thenReturn(List.of(creator));
        when(submissionRepository.findByApplicationIdInOrderByApplicationIdAscSubmittedAtAsc(
                List.of(7))).thenReturn(List.of(historical));
        when(escrowTransactionRepository.findByCampaignIdOrderByCreatedAtDesc(3))
                .thenReturn(List.of());
        when(fileStorage.signedDownloadUrl("submissions/current.mp4"))
                .thenReturn("https://storage.example/current?signed=1");
        when(fileStorage.signedDownloadUrl("submissions/history.mp4"))
                .thenReturn("https://storage.example/history?signed=1");

        CompanyCampaignResponse response = companyService.getCampaign(3, 50);

        CompanyCampaignResponse.ApplicationItem item = response.getApplications().getFirst();
        assertThat(item.videoUrl()).isEqualTo("https://storage.example/current?signed=1");
        assertThat(item.creator()).isEqualTo(
                new CompanyCampaignResponse.CreatorInfo(11, "크리에이터"));
        assertThat(item.submissions()).singleElement().satisfies(submission ->
                assertThat(submission.videoUrl())
                        .isEqualTo("https://storage.example/history?signed=1"));
        assertThat(CompanyCampaignResponse.ApplicationItem.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("videoFileKey");
        assertThat(CompanyCampaignResponse.CompanySubmissionItem.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("videoFileKey");
        assertThat(CompanyCampaignResponse.CreatorInfo.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("email");
    }
}
