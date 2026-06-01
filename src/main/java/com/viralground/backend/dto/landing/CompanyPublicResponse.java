package com.viralground.backend.dto.landing;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 회사 소개 모달용 공개 응답. 비로그인 노출이므로 사업자번호·대표자명·담당자·연락처·주소 등
 * 민감 필드는 record 정의 자체에서 제외해 컴파일 단계에서 누출을 차단한다.
 * 공개 가능한 회사명·산업·홈페이지와, 그 회사가 진행 중인 OPEN 캠페인 요약만 담는다.
 */
public record CompanyPublicResponse(
        String companyName,
        String industry,
        String homepage,
        String introduction,
        String logoUrl,
        List<OpenCampaignItem> openCampaigns
) {

    public record OpenCampaignItem(
            Integer id,
            String title,
            Integer rewardAmount,
            LocalDateTime deadline
    ) {
    }
}
