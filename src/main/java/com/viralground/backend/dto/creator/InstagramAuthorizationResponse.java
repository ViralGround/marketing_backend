package com.viralground.backend.dto.creator;

import java.time.Instant;

/**
 * 서버가 만든 Meta OAuth 동의 URL. state는 응답 URL 안에만 포함되며 별도 필드로 노출하지 않는다.
 */
public record InstagramAuthorizationResponse(String authorizationUrl, Instant expiresAt) {
}
