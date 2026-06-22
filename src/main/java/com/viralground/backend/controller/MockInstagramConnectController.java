package com.viralground.backend.controller;

import com.viralground.backend.config.AuthUser;
import com.viralground.backend.dto.creator.InstagramConnectionResponse;
import com.viralground.backend.service.InstagramConnectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 목 환경 전용 연결 완료 엔드포인트. 실제 Phyllo 는 동의 화면 후 콜백/웹훅으로 연결을 완료하지만,
 * mock provider 에서는 동의 화면이 없으므로 프론트가 이 엔드포인트를 호출해 즉시 CONNECTED 로 만든다.
 * {@code instagram.provider=phyllo} 면 이 빈은 등록되지 않는다.
 */
@RestController
@RequestMapping("/creator/instagram")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "instagram.provider", havingValue = "mock", matchIfMissing = true)
public class MockInstagramConnectController {

    private final InstagramConnectionService connectionService;

    /** 목 즉시 연결 완료. body 로 받은 igUsername(없으면 기본값)을 연결 계정명으로 사용. */
    @PostMapping("/mock-complete")
    public ResponseEntity<InstagramConnectionResponse> mockComplete(
            @AuthenticationPrincipal AuthUser authUser,
            @org.springframework.web.bind.annotation.RequestBody(required = false) Map<String, String> body) {
        int creatorId = authUser.getId();
        String igUsername = body != null && body.get("igUsername") != null
                ? body.get("igUsername") : "creator_" + creatorId;
        return ResponseEntity.ok(
                connectionService.markConnected(creatorId, "mock-account-" + creatorId, igUsername));
    }
}
