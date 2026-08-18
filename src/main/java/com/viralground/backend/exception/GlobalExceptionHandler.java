package com.viralground.backend.exception;

import io.sentry.Sentry;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;
import java.util.Map;

import static com.viralground.backend.logging.RequestCorrelationFilter.MDC_KEY;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AppException.class)
    public ResponseEntity<Map<String, String>> handleAppException(AppException e) {
        ErrorCode ec = e.getErrorCode();
        log.atWarn()
                .addKeyValue("event", "application_error")
                .addKeyValue("errorCode", ec.getCode())
                .addKeyValue("httpStatus", ec.getStatus().value())
                .log("Expected application error");
        return ResponseEntity.status(ec.getStatus())
                .body(errorBody(ec.getMessage(), ec.getCode()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getDefaultMessage())
                .orElse("입력값을 확인해주세요");
        return ResponseEntity.badRequest().body(errorBody(message, "VALIDATION_FAILED"));
    }

    @ExceptionHandler({IllegalArgumentException.class, NumberFormatException.class})
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        log.atDebug()
                .addKeyValue("event", "invalid_argument")
                .addKeyValue("errorType", e.getClass().getSimpleName())
                .log("Invalid argument rejected");
        return ResponseEntity.badRequest().body(errorBody("입력값을 확인해주세요", "INVALID_ARGUMENT"));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoSuchElementException e) {
        return ResponseEntity.status(404).body(errorBody("요청한 리소스를 찾을 수 없습니다", "NOT_FOUND"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(403).body(errorBody("권한이 없습니다", "ACCESS_DENIED"));
    }

    /**
     * AppException 가드를 우회한 FK/UNIQUE 제약 위반의 안전망. 사용자에게 원인 메시지를
     * 그대로 노출하지 않고 일반화된 한국어 메시지를 반환.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrity(DataIntegrityViolationException e) {
        // SQL 예외 메시지는 컬럼 값(이메일·외부 ID 등)을 포함할 수 있어 원문을 콘솔에 남기지 않는다.
        Sentry.captureException(e);
        log.atWarn()
                .addKeyValue("event", "data_integrity_violation")
                .addKeyValue("errorType", e.getClass().getSimpleName())
                .log("Database integrity constraint rejected the operation");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(errorBody("다른 데이터와 연결되어 있어 처리할 수 없습니다.",
                        "DATA_INTEGRITY_VIOLATION"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneral(Exception e) {
        // 상세 stack trace는 개인정보 필터를 거치는 Sentry가 수집하고, 운영 콘솔에는 안전한 유형만 기록한다.
        Sentry.captureException(e);
        log.atError()
                .addKeyValue("event", "unhandled_exception")
                .addKeyValue("errorType", e.getClass().getSimpleName())
                .log("Unhandled exception during request");
        return ResponseEntity.internalServerError()
                .body(errorBody("서버 오류가 발생했습니다", "INTERNAL_SERVER_ERROR"));
    }

    private Map<String, String> errorBody(String message, String code) {
        String requestId = MDC.get(MDC_KEY);
        return Map.of(
                "message", message,
                "code", code,
                "requestId", requestId == null ? "unavailable" : requestId
        );
    }
}
