package com.viralground.backend.service;

import com.viralground.backend.dto.landing.CompanyPublicResponse;
import com.viralground.backend.dto.landing.FeaturedCampaignResponse;
import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.CompanyProfile;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.CampaignApplicationRepository;
import com.viralground.backend.repository.CampaignRepository;
import com.viralground.backend.repository.CompanyProfileRepository;
import com.viralground.backend.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 랜딩 페이지(비로그인) 전용 조회 서비스. 인증이 필요한 {@code /campaigns} 흐름과 분리해
 * 공개 노출 데이터만 다룬다. 대표 캠페인 카드와 회사 소개 모달을 책임진다.
 */
@Service
@RequiredArgsConstructor
public class LandingService {

    /** 랜딩에 노출할 대표 캠페인 최대 건수. 지정은 더 될 수 있어도 카드는 이 수만큼만. */
    private static final int FEATURED_LIMIT = 3;

    private final CampaignRepository campaignRepository;
    private final CampaignApplicationRepository applicationRepository;
    private final CompanyProfileRepository companyProfileRepository;
    private final FileStorage fileStorage;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<FeaturedCampaignResponse> getFeaturedCampaigns() {
        List<Campaign> featured = campaignRepository.findFeaturedOpen(LocalDateTime.now(clock))
                .stream()
                .limit(FEATURED_LIMIT)
                .toList();
        if (featured.isEmpty()) return List.of();

        List<Integer> ids = featured.stream().map(Campaign::getId).toList();
        Map<Integer, Long> countByCampaignId = applicationRepository.countByCampaignIdIn(ids).stream()
                .collect(Collectors.toMap(
                        CampaignApplicationRepository.CampaignCountRow::getCampaignId,
                        CampaignApplicationRepository.CampaignCountRow::getCount));

        // 작성자가 기업 프로필을 가진 경우에만 회사 소개 모달을 띄울 수 있고, 로고도 그때만 노출한다.
        // (admin 직접 생성 캠페인은 프로필이 없으므로 companyMemberId·logoUrl 를 null 로 둔다.)
        List<Integer> creatorIds = featured.stream()
                .map(Campaign::getCreatedById)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Integer, CompanyProfile> profileByMemberId = creatorIds.isEmpty()
                ? Map.of()
                : companyProfileRepository.findByMemberIdIn(creatorIds).stream()
                        .collect(Collectors.toMap(CompanyProfile::getMemberId, p -> p, (a, b) -> a));

        return featured.stream()
                .map(c -> {
                    CompanyProfile profile = profileByMemberId.get(c.getCreatedById());
                    return new FeaturedCampaignResponse(
                            c.getId(),
                            c.getTitle(),
                            c.getBrandName(),
                            c.getRewardAmount(),
                            c.getDeadline(),
                            c.getMaxParticipants(),
                            countByCampaignId.getOrDefault(c.getId(), 0L).intValue(),
                            resolveThumbUrl(c),
                            profile != null ? c.getCreatedById() : null,
                            profile != null ? resolveLogoUrl(profile) : resolveCampaignLogoUrl(c),
                            c.getBrandIntroduction());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public CompanyPublicResponse getCompanyPublic(Integer memberId) {
        CompanyProfile profile = companyProfileRepository.findByMemberId(memberId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        List<CompanyPublicResponse.OpenCampaignItem> openCampaigns =
                campaignRepository.findOpenByCreator(memberId, LocalDateTime.now(clock)).stream()
                        .map(c -> new CompanyPublicResponse.OpenCampaignItem(
                                c.getId(), c.getTitle(), c.getRewardAmount(), c.getDeadline()))
                        .toList();

        return new CompanyPublicResponse(
                profile.getCompanyName(),
                profile.getIndustry(),
                profile.getHomepage(),
                profile.getIntroduction(),
                resolveLogoUrl(profile),
                openCampaigns);
    }

    private String resolveThumbUrl(Campaign c) {
        if (c.getThumbnailFileKey() != null && !c.getThumbnailFileKey().isBlank()) {
            return fileStorage.signedDownloadUrl(c.getThumbnailFileKey());
        }
        return c.getThumbnailUrl();
    }

    private String resolveLogoUrl(CompanyProfile p) {
        if (p.getLogoFileKey() != null && !p.getLogoFileKey().isBlank()) {
            return fileStorage.signedDownloadUrl(p.getLogoFileKey());
        }
        return null;
    }

    /** 관리자 직접 생성 캠페인의 브랜드 로고 서명 URL. 없으면 null. */
    private String resolveCampaignLogoUrl(Campaign c) {
        if (c.getBrandLogoFileKey() != null && !c.getBrandLogoFileKey().isBlank()) {
            return fileStorage.signedDownloadUrl(c.getBrandLogoFileKey());
        }
        return null;
    }
}
