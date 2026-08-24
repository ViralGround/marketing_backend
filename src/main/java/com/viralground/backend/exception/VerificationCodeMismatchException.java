package com.viralground.backend.exception;

/** Mismatch whose attempt-counter mutation must commit before returning the error. */
public final class VerificationCodeMismatchException extends AppException {
    public VerificationCodeMismatchException() {
        super(ErrorCode.VERIFICATION_CODE_MISMATCH);
    }
}
