package com.viralground.backend.instagram.phyllo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phyllo 콘텐츠 인게이지먼트 지표(snake_case → record 매핑). 누락 필드는 {@code null} → 호출측에서 0 처리.
 *
 * <p><b>주의:</b> 필드명은 Phyllo 문서 스키마 기준이다. 실제 연결 계정 응답으로 최종 검증이 필요하며,
 * 콘텐츠가 매칭됐는데 모든 값이 0이면 {@link PhylloInstagramConnectionProvider} 가 WARN 로그로 불일치를 알린다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record PhylloEngagement(
        @JsonProperty("like_count") Long likeCount,
        @JsonProperty("comment_count") Long commentCount,
        @JsonProperty("view_count") Long viewCount,
        @JsonProperty("share_count") Long shareCount) {}
