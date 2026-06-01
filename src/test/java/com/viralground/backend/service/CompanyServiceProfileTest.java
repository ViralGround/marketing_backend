package com.viralground.backend.service;

import com.viralground.backend.dto.company.CompanyProfileResponse;
import com.viralground.backend.dto.company.UpdateCompanyProfileRequest;
import com.viralground.backend.entity.CompanyProfile;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.*;
import com.viralground.backend.storage.FileStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanyServiceProfileTest {

    @Mock CampaignRepository campaignRepository;
    @Mock CampaignApplicationRepository applicationRepository;
    @Mock MemberRepository memberRepository;
    @Mock EscrowService escrowService;
    @Mock EscrowTransactionRepository escrowTransactionRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock ApplicationSubmissionRepository submissionRepository;
    @Mock FileStorage fileStorage;
    @Mock CompanyProfileRepository companyProfileRepository;

    @InjectMocks
    CompanyService companyService;

    private CompanyProfile profile() {
        return CompanyProfile.builder()
                .memberId(10)
                .companyName("주식회사 텐")
                .industry("패션")
                .homepage("https://ten.example.com")
                .introduction("우리는 텐입니다.")
                .build();
    }

    @Test
    void getMyProfile_로고키_있으면_서명URL로_변환() {
        // given
        CompanyProfile p = profile();
        p.setLogoFileKey("thumbnails/logo-1");
        when(companyProfileRepository.findByMemberId(10)).thenReturn(Optional.of(p));
        when(fileStorage.signedDownloadUrl("thumbnails/logo-1")).thenReturn("https://signed/logo-1");

        // when
        CompanyProfileResponse res = companyService.getMyProfile(10);

        // then
        assertThat(res.companyName()).isEqualTo("주식회사 텐");
        assertThat(res.introduction()).isEqualTo("우리는 텐입니다.");
        assertThat(res.logoUrl()).isEqualTo("https://signed/logo-1");
    }

    @Test
    void getMyProfile_로고_없으면_logoUrl_null() {
        // given
        when(companyProfileRepository.findByMemberId(10)).thenReturn(Optional.of(profile()));

        // when
        CompanyProfileResponse res = companyService.getMyProfile(10);

        // then
        assertThat(res.logoUrl()).isNull();
    }

    @Test
    void getMyProfile_프로필_없으면_예외() {
        // given
        when(companyProfileRepository.findByMemberId(99)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> companyService.getMyProfile(99))
                .isInstanceOf(AppException.class);
    }

    @Test
    void updateMyProfile_소개글_산업_홈페이지_로고를_갱신() {
        // given
        CompanyProfile p = profile();
        when(companyProfileRepository.findByMemberId(10)).thenReturn(Optional.of(p));
        when(fileStorage.exists("thumbnails/new-logo")).thenReturn(true);

        UpdateCompanyProfileRequest req = new UpdateCompanyProfileRequest();
        req.setIntroduction("새 소개글");
        req.setIndustry("뷰티");
        req.setHomepage("https://new.example.com");
        req.setLogoFileKey("thumbnails/new-logo");

        // when
        companyService.updateMyProfile(10, req);

        // then
        assertThat(p.getIntroduction()).isEqualTo("새 소개글");
        assertThat(p.getIndustry()).isEqualTo("뷰티");
        assertThat(p.getHomepage()).isEqualTo("https://new.example.com");
        assertThat(p.getLogoFileKey()).isEqualTo("thumbnails/new-logo");
    }

    @Test
    void updateMyProfile_로고키_빈문자열이면_제거() {
        // given
        CompanyProfile p = profile();
        p.setLogoFileKey("thumbnails/old");
        when(companyProfileRepository.findByMemberId(10)).thenReturn(Optional.of(p));

        UpdateCompanyProfileRequest req = new UpdateCompanyProfileRequest();
        req.setLogoFileKey("");

        // when
        companyService.updateMyProfile(10, req);

        // then
        assertThat(p.getLogoFileKey()).isNull();
    }

    @Test
    void updateMyProfile_존재하지_않는_로고키면_예외() {
        // given
        CompanyProfile p = profile();
        when(companyProfileRepository.findByMemberId(10)).thenReturn(Optional.of(p));
        when(fileStorage.exists("thumbnails/ghost")).thenReturn(false);

        UpdateCompanyProfileRequest req = new UpdateCompanyProfileRequest();
        req.setLogoFileKey("thumbnails/ghost");

        // when & then
        assertThatThrownBy(() -> companyService.updateMyProfile(10, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SUBMISSION_NOT_FOUND);
    }

    @Test
    void updateMyProfile_프로필_없으면_예외() {
        // given
        when(companyProfileRepository.findByMemberId(99)).thenReturn(Optional.empty());
        UpdateCompanyProfileRequest req = new UpdateCompanyProfileRequest();

        // when & then
        assertThatThrownBy(() -> companyService.updateMyProfile(99, req))
                .isInstanceOf(AppException.class);
    }
}
