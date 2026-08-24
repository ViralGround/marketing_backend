package com.viralground.backend.notification;

/**
 * Fixed, non-transactional templates that may be queued by the sealed
 * preproduction email-validation probe. Request data can select only one of
 * these values; recipients and message content are never caller-controlled.
 */
public enum StagingEmailValidationTemplate {
    EMAIL_VERIFICATION_CODE,
    PASSWORD_RESET_CODE,
    CREATOR_SIGNUP_ADMIN,
    MEMBER_STATUS_RESULT,
    CAMPAIGN_APPLICATION_ADMIN,
    CONTACT_RECEIVED_ADMIN,
    APPLICATION_RESULT,
    APPLICATION_CHANGES_REQUESTED
}
