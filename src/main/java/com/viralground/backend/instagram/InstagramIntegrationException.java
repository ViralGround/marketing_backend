package com.viralground.backend.instagram;

import org.springframework.http.HttpStatus;

/** 사용자에게 upstream 세부정보나 토큰을 노출하지 않는 Instagram 연동 예외. */
public class InstagramIntegrationException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public InstagramIntegrationException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public InstagramIntegrationException(String code, String message, HttpStatus status, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
