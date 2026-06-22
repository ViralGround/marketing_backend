package com.viralground.backend.dto.creator;

import com.viralground.backend.entity.ConnectionStatus;
import com.viralground.backend.entity.CreatorInstagramConnection;

import java.time.LocalDateTime;

/**
 * 크리에이터 인스타그램 연동 상태 응답.
 *
 * @param connected          CONNECTED 여부(프론트 분기 편의)
 * @param status             연동 상태(PENDING/CONNECTED/ERROR/DISCONNECTED, 레코드 없으면 "NONE")
 * @param igUsername         연결된 인스타 계정명(연결 시)
 * @param profileInstagramId 프로필에 등록된 인스타 아이디(연결 게이트·일치 안내용, 없으면 null)
 * @param connectedAt        연결 완료 시각
 * @param lastSyncedAt       마지막 동기화 시각
 * @param lastError          마지막 동기화/연결 오류 메시지
 */
public record InstagramConnectionResponse(
        boolean connected,
        String status,
        String igUsername,
        String profileInstagramId,
        LocalDateTime connectedAt,
        LocalDateTime lastSyncedAt,
        String lastError) {

    /** 연동 레코드가 없는 미연결 상태(프로필 아이디는 게이트 판단용으로 함께 전달). */
    public static InstagramConnectionResponse none(String profileInstagramId) {
        return new InstagramConnectionResponse(false, "NONE", null, profileInstagramId, null, null, null);
    }

    public static InstagramConnectionResponse from(CreatorInstagramConnection conn, String profileInstagramId) {
        return new InstagramConnectionResponse(
                conn.getStatus() == ConnectionStatus.CONNECTED,
                conn.getStatus().name(),
                conn.getIgUsername(),
                profileInstagramId,
                conn.getConnectedAt(),
                conn.getLastSyncedAt(),
                conn.getLastError());
    }
}
