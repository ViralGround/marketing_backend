package com.viralground.backend.controller;

import com.viralground.backend.config.AuthUser;
import com.viralground.backend.dto.creator.ConnectTokenResponse;
import com.viralground.backend.dto.creator.InstagramConnectionResponse;
import com.viralground.backend.service.InstagramConnectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 크리에이터 인스타그램 연동 API. 연결 토큰 발급 → (Connect SDK 동의) → 연결 콜백 → 상태 조회 / 해제.
 * creatorId 는 JWT 인증 주체에서 얻는다.
 */
@RestController
@RequestMapping("/creator/instagram")
@RequiredArgsConstructor
public class CreatorInstagramController {

    private final InstagramConnectionService connectionService;

    /** 연결 토큰 발급. 프론트는 token+userId+environment 로 Connect SDK 를 초기화한다. */
    @PostMapping("/connect-token")
    public ResponseEntity<ConnectTokenResponse> connectToken(@AuthenticationPrincipal AuthUser authUser) {
        return ResponseEntity.ok(connectionService.getConnectToken(authUser.getId()));
    }

    /**
     * 연결 완료 콜백. Connect SDK 의 {@code accountConnected} 이벤트 후 프론트가 호출한다.
     * body: {@code {accountId, workplatformId?}}.
     */
    @PostMapping("/connect-callback")
    public ResponseEntity<InstagramConnectionResponse> connectCallback(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestBody Map<String, String> body) {
        String accountId = body.get("accountId");
        String workplatformId = body.get("workplatformId");
        return ResponseEntity.ok(
                connectionService.completeConnection(authUser.getId(), accountId, workplatformId));
    }

    /** 현재 연동 상태 조회. */
    @GetMapping("/connection")
    public ResponseEntity<InstagramConnectionResponse> connection(@AuthenticationPrincipal AuthUser authUser) {
        return ResponseEntity.ok(connectionService.getConnection(authUser.getId()));
    }

    /** 연동 해제. */
    @DeleteMapping("/connection")
    public ResponseEntity<Void> disconnect(@AuthenticationPrincipal AuthUser authUser) {
        connectionService.disconnect(authUser.getId());
        return ResponseEntity.noContent().build();
    }
}
