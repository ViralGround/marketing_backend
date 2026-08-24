package com.viralground.backend.exception;

/**
 * Account-existence-safe password reset rejection. Every unknown, expired,
 * exhausted, withdrawn, and mismatched reset attempt exposes the same response.
 */
public final class PasswordResetRejectedException extends AppException {
    public PasswordResetRejectedException() {
        super(ErrorCode.PASSWORD_RESET_INVALID);
    }
}
