package com.viralground.backend.entity;

/**
 * 크리에이터 인스타그램 연동 상태.
 * PENDING(연결 토큰 발급, 동의 대기) → CONNECTED(연결 완료) → DISCONNECTED(해제).
 * ERROR 는 동기화/연결 중 오류 발생 상태.
 */
public enum ConnectionStatus {
    PENDING, CONNECTED, ERROR, DISCONNECTED
}
