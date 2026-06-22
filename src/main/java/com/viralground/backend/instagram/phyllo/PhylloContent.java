package com.viralground.backend.instagram.phyllo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phyllo 콘텐츠(GET /v1/social/contents)의 필요한 필드만 매핑한 DTO.
 *
 * @param url        콘텐츠 permalink (릴스 URL 매칭용)
 * @param externalId 플랫폼 측 미디어 id
 * @param format     콘텐츠 형식(VIDEO/IMAGE 등, 참고용)
 * @param engagement 인게이지먼트 지표
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record PhylloContent(
        String url,
        @JsonProperty("external_id") String externalId,
        String format,
        PhylloEngagement engagement) {}
