package com.viralground.backend.service;

import com.viralground.backend.dto.landing.CompanyPublicResponse;
import com.viralground.backend.dto.landing.FeaturedCampaignResponse;
import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.CampaignStatus;
import com.viralground.backend.entity.CompanyProfile;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.repository.CampaignApplicationRepository;
import com.viralground.backend.repository.CampaignRepository;
import com.viralground.backend.repository.CompanyProfileRepository;
import com.viralground.backend.storage.FileStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LandingServiceTest {

    @Mock CampaignRepository campaignRepository;
    @Mock CampaignApplicationRepository applicationRepository;
    @Mock CompanyProfileRepository companyProfileRepository;
    @Mock FileStorage fileStorage;

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-06-01T00:00:00Z"), ZoneId.of("UTC"));

    private LandingService service() {
        return new LandingService(campaignRepository, applicationRepository,
                companyProfileRepository, fileStorage, clock);
    }

    private Campaign campaign(Integer id, Integer createdById, Integer featuredOrder) {
        return Campaign.builder()
                .id(id)
                .title("캠페인 " + id)
                .description("설명")
                .brandName("브랜드 " + id)
                .rewardAmount(50_000)
                .maxParticipants(10)
                .status(CampaignStatus.OPEN)
                .featuredOrder(featuredOrder)
                .createdById(createdById)
                .build();
    }

    private CampaignApplicationRepository.CampaignCountRow countRow(Integer campaignId, long count) {
        return new CampaignApplicationRepository.CampaignCountRow() {
            public Integer getCampaignId() { return campaignId; }
            public Long getCount() { return count; }
        };
    }

    @Test
    void getFeaturedCampaigns_최대_3건만_반환() {
        // given — featured 4건이 내려와도 카드는 3건까지만
        List<Campaign> four = List.of(
                campaign(1, 10, 1), campaign(2, 10, 2),
                campaign(3, 10, 3), campaign(4, 10, 4));
        when(campaignRepository.findFeaturedOpen(LocalDateTime.now(clock))).thenReturn(four);
        when(applicationRepository.countByCampaignIdIn(anyList())).thenReturn(List.of());
        when(companyProfileRepository.findByMemberIdIn(anyList())).thenReturn(List.of());

        // when
        List<FeaturedCampaignResponse> result = service().getFeaturedCampaigns();

        // then
        assertThat(result).hasSize(3)
                .extracting(FeaturedCampaignResponse::id)
                .containsExactly(1, 2, 3);
    }

    @Test
    void getFeaturedCampaigns_지원자수를_매핑한다() {
        // given
        when(campaignRepository.findFeaturedOpen(LocalDateTime.now(clock)))
                .thenReturn(List.of(campaign(1, 10, 1)));
        when(applicationRepository.countByCampaignIdIn(anyList()))
                .thenReturn(List.of(countRow(1, 7)));
        when(companyProfileRepository.findByMemberIdIn(anyList())).thenReturn(List.of());

        // when
        List<FeaturedCampaignResponse> result = service().getFeaturedCampaigns();

        // then
        assertThat(result.get(0).applicationCount()).isEqualTo(7);
    }

    @Test
    void getFeaturedCampaigns_회사프로필_있으면_companyMemberId_없으면_null() {
        // given — createdById=10 은 회사 프로필 있음, 20 은 admin 생성으로 프로필 없음
        when(campaignRepository.findFeaturedOpen(LocalDateTime.now(clock)))
                .thenReturn(List.of(campaign(1, 10, 1), campaign(2, 20, 2)));
        when(applicationRepository.countByCampaignIdIn(anyList())).thenReturn(List.of());
        CompanyProfile profile = CompanyProfile.builder().memberId(10).companyName("주식회사 텐").build();
        when(companyProfileRepository.findByMemberIdIn(anyList())).thenReturn(List.of(profile));

        // when
        List<FeaturedCampaignResponse> result = service().getFeaturedCampaigns();

        // then
        assertThat(result.get(0).companyMemberId()).isEqualTo(10);
        assertThat(result.get(1).companyMemberId()).isNull();
    }

    @Test
    void getFeaturedCampaigns_회사_로고가_있으면_서명URL을_담는다() {
        // given — createdById=10 기업 프로필에 로고 존재
        when(campaignRepository.findFeaturedOpen(LocalDateTime.now(clock)))
                .thenReturn(List.of(campaign(1, 10, 1)));
        when(applicationRepository.countByCampaignIdIn(anyList())).thenReturn(List.of());
        CompanyProfile profile = CompanyProfile.builder()
                .memberId(10).companyName("주식회사 텐").logoFileKey("thumbnails/logo-10").build();
        when(companyProfileRepository.findByMemberIdIn(anyList())).thenReturn(List.of(profile));
        lenient().when(fileStorage.signedDownloadUrl("thumbnails/logo-10"))
                .thenReturn("https://signed/logo-10");

        // when
        List<FeaturedCampaignResponse> result = service().getFeaturedCampaigns();

        // then
        assertThat(result.get(0).logoUrl()).isEqualTo("https://signed/logo-10");
    }

    @Test
    void getFeaturedCampaigns_없으면_빈_리스트() {
        // given
        when(campaignRepository.findFeaturedOpen(LocalDateTime.now(clock))).thenReturn(List.of());

        // when
        List<FeaturedCampaignResponse> result = service().getFeaturedCampaigns();

        // then
        assertThat(result).isEmpty();
    }

    @Test
    void getCompanyPublic_공개필드와_OPEN_캠페인_목록을_반환() {
        // given
        CompanyProfile profile = CompanyProfile.builder()
                .memberId(10)
                .companyName("주식회사 텐")
                .industry("패션")
                .homepage("https://ten.example.com")
                .introduction("우리는 텐입니다.")
                .logoFileKey("thumbnails/logo-10")
                .businessNumber("123-45-67890")
                .contactPhone("010-0000-0000")
                .build();
        when(companyProfileRepository.findByMemberId(10)).thenReturn(java.util.Optional.of(profile));
        when(campaignRepository.findOpenByCreator(10, LocalDateTime.now(clock)))
                .thenReturn(List.of(campaign(1, 10, null)));
        lenient().when(fileStorage.signedDownloadUrl("thumbnails/logo-10"))
                .thenReturn("https://signed/logo-10");

        // when
        CompanyPublicResponse result = service().getCompanyPublic(10);

        // then
        assertThat(result.companyName()).isEqualTo("주식회사 텐");
        assertThat(result.industry()).isEqualTo("패션");
        assertThat(result.homepage()).isEqualTo("https://ten.example.com");
        assertThat(result.introduction()).isEqualTo("우리는 텐입니다.");
        assertThat(result.logoUrl()).isEqualTo("https://signed/logo-10");
        assertThat(result.openCampaigns()).hasSize(1)
                .extracting(CompanyPublicResponse.OpenCampaignItem::id).containsExactly(1);
    }

    @Test
    void getCompanyPublic_프로필_없으면_예외() {
        // given
        when(companyProfileRepository.findByMemberId(99)).thenReturn(java.util.Optional.empty());

        // when & then
        assertThatThrownBy(() -> service().getCompanyPublic(99))
                .isInstanceOf(AppException.class);
    }

    @Test
    void getFeaturedCampaigns_thumbnailFileKey_있으면_서명URL_생성() {
        // given
        Campaign c = campaign(1, 10, 1);
        c.setThumbnailFileKey("key-1");
        when(campaignRepository.findFeaturedOpen(LocalDateTime.now(clock))).thenReturn(List.of(c));
        when(applicationRepository.countByCampaignIdIn(anyList())).thenReturn(List.of());
        when(companyProfileRepository.findByMemberIdIn(anyList())).thenReturn(List.of());
        lenient().when(fileStorage.signedDownloadUrl("key-1")).thenReturn("https://signed/key-1");

        // when
        List<FeaturedCampaignResponse> result = service().getFeaturedCampaigns();

        // then
        assertThat(result.get(0).thumbnailUrl()).isEqualTo("https://signed/key-1");
    }
}
