package com.viralground.backend.dto.campaign;

import com.viralground.backend.entity.ApplicationSubmission;
import com.viralground.backend.entity.SubmissionReviewStatus;

import java.time.LocalDateTime;

/** 제출 이력 한 건의 공개용 스냅샷. 크리에이터/기업/관리자 응답 공통으로 사용. */
public record SubmissionHistoryItem(
        Integer id,
        String videoFileKey,
        String videoContentType,
        Long videoSizeBytes,
        String submissionUrl,
        SubmissionReviewStatus status,
        String reviewComment,
        LocalDateTime submittedAt,
        LocalDateTime reviewedAt
) {
    public static SubmissionHistoryItem from(ApplicationSubmission s) {
        return new SubmissionHistoryItem(
                s.getId(),
                s.getVideoFileKey(),
                s.getVideoContentType(),
                s.getVideoSizeBytes(),
                s.getSubmissionUrl(),
                s.getStatus(),
                s.getReviewComment(),
                s.getSubmittedAt(),
                s.getReviewedAt()
        );
    }
}
