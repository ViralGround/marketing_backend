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
    PENDING_APPROVAL("PENDING_APPROVAL", "관리자 승인 대기 중입니다. 승인까지 영업일 기준 일주일 이상 걸릴 수 있으며, 승인 후 로그인할 수 있어요.", HttpStatus.FORBIDDEN),
    REJECTED("REJECTED", "가입이 거절되었습니다. 문의는 관리자에게 연락해주세요.", HttpStatus.FORBIDDEN),
    DUPLICATE_EMAIL("DUPLICATE_EMAIL", "이미 등록된 이메일입니다", HttpStatus.CONFLICT),
    MISSING_TOKEN("MISSING_TOKEN", "토큰이 없습니다", HttpStatus.BAD_REQUEST),
    INVALID_TOKEN("INVALID_TOKEN", "유효하지 않은 토큰입니다", HttpStatus.BAD_REQUEST),
    EXPIRED_TOKEN("EXPIRED_TOKEN", "만료된 토큰입니다", HttpStatus.BAD_REQUEST),
    INVALID_EMAIL_FORMAT("INVALID_EMAIL_FORMAT", "올바른 이메일 형식이 아닙니다", HttpStatus.BAD_REQUEST),
    VERIFICATION_CODE_NOT_REQUESTED("VERIFICATION_CODE_NOT_REQUESTED", "인증 요청을 먼저 해주세요", HttpStatus.BAD_REQUEST),
    VERIFICATION_CODE_EXPIRED("VERIFICATION_CODE_EXPIRED", "인증 코드가 만료되었습니다. 다시 요청해주세요.", HttpStatus.BAD_REQUEST),
    VERIFICATION_CODE_MISMATCH("VERIFICATION_CODE_MISMATCH", "인증 코드가 올바르지 않습니다", HttpStatus.BAD_REQUEST),
    VERIFICATION_ATTEMPTS_EXCEEDED("VERIFICATION_ATTEMPTS_EXCEEDED", "인증 시도 횟수를 초과했습니다. 다시 요청해주세요.", HttpStatus.TOO_MANY_REQUESTS),
    EMAIL_NOT_VERIFIED_FOR_SIGNUP("EMAIL_NOT_VERIFIED_FOR_SIGNUP", "이메일 인증을 먼저 완료해주세요", HttpStatus.BAD_REQUEST),
    INVALID_VERIFIED_TOKEN("INVALID_VERIFIED_TOKEN", "이메일 인증이 유효하지 않습니다. 다시 인증해주세요.", HttpStatus.BAD_REQUEST),
    UNDERAGE("UNDERAGE", "만 14세 미만은 가입할 수 없습니다.", HttpStatus.BAD_REQUEST),
    AGREEMENT_REQUIRED("AGREEMENT_REQUIRED", "필수 약관에 동의해야 가입할 수 있습니다.", HttpStatus.BAD_REQUEST),
    CAMPAIGN_NOT_FOUND("CAMPAIGN_NOT_FOUND", "캠페인을 찾을 수 없습니다", HttpStatus.NOT_FOUND),
    CAMPAIGN_CLOSED("CAMPAIGN_CLOSED", "마감된 캠페인입니다.", HttpStatus.BAD_REQUEST),
    CAMPAIGN_HAS_DEPENDENCIES("CAMPAIGN_HAS_DEPENDENCIES", "지원자 또는 예치금 거래가 있는 캠페인은 삭제할 수 없습니다. 대신 '숨김' 처리해 주세요.", HttpStatus.CONFLICT),
    FEATURED_LIMIT_EXCEEDED("FEATURED_LIMIT_EXCEEDED", "대표 캠페인은 최대 3건까지 지정할 수 있습니다.", HttpStatus.BAD_REQUEST),
    APPLICATION_NOT_FOUND("APPLICATION_NOT_FOUND", "지원 내역을 찾을 수 없습니다", HttpStatus.NOT_FOUND),
    ALREADY_APPLIED("ALREADY_APPLIED", "이미 지원한 캠페인입니다", HttpStatus.CONFLICT),
    FORBIDDEN("FORBIDDEN", "권한이 없습니다", HttpStatus.FORBIDDEN),
    SELF_DELETE_FORBIDDEN("SELF_DELETE_FORBIDDEN", "자기 자신은 삭제할 수 없습니다", HttpStatus.FORBIDDEN),
    INVALID_ESCROW_STATE("INVALID_ESCROW_STATE", "현재 예치금 상태에서 허용되지 않는 작업입니다", HttpStatus.BAD_REQUEST),
    INSUFFICIENT_ESCROW_BALANCE("INSUFFICIENT_ESCROW_BALANCE", "예치금 잔액이 부족합니다", HttpStatus.BAD_REQUEST),
    INVALID_CAMPAIGN_INPUT("INVALID_CAMPAIGN_INPUT", "캠페인 입력값이 올바르지 않습니다", HttpStatus.BAD_REQUEST),
    RATE_LIMIT_EXCEEDED("RATE_LIMIT_EXCEEDED", "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.", HttpStatus.TOO_MANY_REQUESTS),
    EMAIL_SEND_FAILED("EMAIL_SEND_FAILED", "인증 메일 발송에 실패했습니다. 잠시 후 다시 시도해주세요.", HttpStatus.SERVICE_UNAVAILABLE),
    INVALID_VIDEO_FORMAT("INVALID_VIDEO_FORMAT", "지원하지 않는 영상 형식입니다. mp4, mov, webm 파일만 업로드할 수 있어요.", HttpStatus.BAD_REQUEST),
    VIDEO_TOO_LARGE("VIDEO_TOO_LARGE", "업로드 가능한 영상 용량을 초과했습니다.", HttpStatus.BAD_REQUEST),
    INVALID_IMAGE_FORMAT("INVALID_IMAGE_FORMAT", "지원하지 않는 이미지 형식입니다. jpg, png, webp 파일만 업로드할 수 있어요.", HttpStatus.BAD_REQUEST),
    IMAGE_TOO_LARGE("IMAGE_TOO_LARGE", "업로드 가능한 이미지 용량(10MB)을 초과했습니다.", HttpStatus.BAD_REQUEST),
    SUBMISSION_NOT_FOUND("SUBMISSION_NOT_FOUND", "영상 파일을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    INVALID_SIGNATURE("INVALID_SIGNATURE", "유효하지 않은 서명입니다.", HttpStatus.UNAUTHORIZED),
    EXPIRED_SIGNATURE("EXPIRED_SIGNATURE", "서명이 만료되었습니다. 다시 요청해주세요.", HttpStatus.UNAUTHORIZED),
    REVIEW_NOT_ALLOWED_YET("REVIEW_NOT_ALLOWED_YET", "정산 완료 후에만 리뷰를 남길 수 있습니다.", HttpStatus.BAD_REQUEST),
    REVIEW_ALREADY_EXISTS("REVIEW_ALREADY_EXISTS", "이미 리뷰를 작성했습니다.", HttpStatus.CONFLICT),
    INVALID_RATING("INVALID_RATING", "평점은 1~5 사이여야 합니다.", HttpStatus.BAD_REQUEST),
    METRIC_FORBIDDEN("METRIC_FORBIDDEN", "정산 완료된 작업에만 성과를 입력할 수 있습니다.", HttpStatus.FORBIDDEN);

    private final String code;
    private final String message;
    private final HttpStatus status;
}
