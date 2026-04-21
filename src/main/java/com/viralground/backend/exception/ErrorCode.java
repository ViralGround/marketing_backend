package com.viralground.backend.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    USER_NOT_FOUND("USER_NOT_FOUND", "존재하지 않는 계정입니다", HttpStatus.NOT_FOUND),
    INVALID_PASSWORD("INVALID_PASSWORD", "이메일 또는 비밀번호가 올바르지 않습니다", HttpStatus.UNAUTHORIZED),
    EMAIL_NOT_VERIFIED("EMAIL_NOT_VERIFIED", "이메일 인증이 필요합니다. 가입 시 발송된 인증 메일을 확인해주세요.", HttpStatus.FORBIDDEN),
    PENDING_APPROVAL("PENDING_APPROVAL", "관리자 승인 대기 중입니다. 승인 후 로그인할 수 있어요.", HttpStatus.FORBIDDEN),
    REJECTED("REJECTED", "가입이 거절되었습니다. 문의는 관리자에게 연락해주세요.", HttpStatus.FORBIDDEN),
    DUPLICATE_EMAIL("DUPLICATE_EMAIL", "이미 등록된 이메일입니다", HttpStatus.CONFLICT),
    MISSING_TOKEN("MISSING_TOKEN", "토큰이 없습니다", HttpStatus.BAD_REQUEST),
    INVALID_TOKEN("INVALID_TOKEN", "유효하지 않은 토큰입니다", HttpStatus.BAD_REQUEST),
    EXPIRED_TOKEN("EXPIRED_TOKEN", "만료된 토큰입니다", HttpStatus.BAD_REQUEST),
    CAMPAIGN_NOT_FOUND("CAMPAIGN_NOT_FOUND", "캠페인을 찾을 수 없습니다", HttpStatus.NOT_FOUND),
    APPLICATION_NOT_FOUND("APPLICATION_NOT_FOUND", "지원 내역을 찾을 수 없습니다", HttpStatus.NOT_FOUND),
    ALREADY_APPLIED("ALREADY_APPLIED", "이미 지원한 캠페인입니다", HttpStatus.CONFLICT),
    FORBIDDEN("FORBIDDEN", "권한이 없습니다", HttpStatus.FORBIDDEN),
    SELF_DELETE_FORBIDDEN("SELF_DELETE_FORBIDDEN", "자기 자신은 삭제할 수 없습니다", HttpStatus.FORBIDDEN),
    INVALID_ESCROW_STATE("INVALID_ESCROW_STATE", "현재 예치금 상태에서 허용되지 않는 작업입니다", HttpStatus.BAD_REQUEST),
    INSUFFICIENT_ESCROW_BALANCE("INSUFFICIENT_ESCROW_BALANCE", "예치금 잔액이 부족합니다", HttpStatus.BAD_REQUEST),
    INVALID_CAMPAIGN_INPUT("INVALID_CAMPAIGN_INPUT", "캠페인 입력값이 올바르지 않습니다", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus status;
}
