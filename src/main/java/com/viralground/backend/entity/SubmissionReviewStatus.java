package com.viralground.backend.entity;

/**
 * ApplicationSubmission 한 건의 검토 결과 상태.
 * CampaignApplication.status 와 별개로, 각 제출 이력의 처리 결과를 기록한다.
 */
public enum SubmissionReviewStatus {
    SUBMITTED,          // 검토 대기
    APPROVED,           // 이 제출 건이 최종 승인됨 (application 은 SETTLED)
    CHANGES_REQUESTED,  // 수정 요청 — 재제출 필요
    REJECTED            // 이 제출 건이 거절됨 (application 도 REJECTED)
}
