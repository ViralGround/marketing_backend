package com.viralground.backend.event;

/**
 * 크리에이터 인스타그램 연결 완료 이벤트. 연결 직후 초기 릴스 동기화를 트리거한다.
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 로 받아 연결 레코드 커밋 이후 동기화한다.
 *
 * @param creatorId 연결된 크리에이터 id
 */
public record InstagramConnectedEvent(int creatorId) {}
