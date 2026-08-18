package com.viralground.backend.controller;

import com.viralground.backend.config.AuthUser;
import com.viralground.backend.dto.creator.InstagramAuthorizationResponse;
import com.viralground.backend.dto.creator.InstagramConnectionResponse;
import com.viralground.backend.entity.Role;
import com.viralground.backend.instagram.InstagramIntegrationException;
import com.viralground.backend.service.InstagramConnectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 크리에이터 인스타그램 연동 API. OAuth 시작 → Meta의 public callback → 상태 조회 / 권한 철회.
 * creatorId 는 JWT 인증 주체에서 얻는다.
 */
@RestController
@RequestMapping("/creator/instagram")
@RequiredArgsConstructor
public class CreatorInstagramController {

    private final InstagramConnectionService connectionService;

    /** Meta 동의 화면 URL을 발급한다. 브라우저는 응답 URL로 이동하면 된다. */
    @PostMapping("/authorize")
    public ResponseEntity<InstagramAuthorizationResponse> authorize(
            @AuthenticationPrincipal AuthUser authUser) {
        requireCreator(authUser);
        return ResponseEntity.ok(connectionService.beginAuthorization(authUser.getId()));
    }

    /** 현재 연동 상태 조회. */
    @GetMapping("/connection")
    public ResponseEntity<InstagramConnectionResponse> connection(@AuthenticationPrincipal AuthUser authUser) {
        requireCreator(authUser);
        return ResponseEntity.ok(connectionService.getConnection(authUser.getId()));
    }

    /** 연동 해제. */
    @DeleteMapping("/connection")
    public ResponseEntity<Void> disconnect(@AuthenticationPrincipal AuthUser authUser) {
        requireCreator(authUser);
        connectionService.disconnect(authUser.getId());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(InstagramIntegrationException.class)
    public ResponseEntity<Map<String, String>> instagramError(InstagramIntegrationException error) {
        return ResponseEntity.status(error.getStatus())
                .body(Map.of("code", error.getCode(), "message", error.getMessage()));
    }

    private static void requireCreator(AuthUser authUser) {
        if (authUser == null || authUser.getRole() != Role.CREATOR) {
            throw new InstagramIntegrationException("INSTAGRAM_CREATOR_ONLY",
                    "크리에이터 계정만 Instagram을 연결할 수 있습니다",
                    org.springframework.http.HttpStatus.FORBIDDEN);
        }
    }
}
