package com.viralground.backend.dto.campaign;

/**
 * 크리에이터가 캠페인 지원 건에 작업 결과물을 제출할 때의 요청 바디.
 * - 파일 업로드 방식: {@code videoFileKey} + {@code videoContentType} + {@code videoSizeBytes}
 * - 레거시(외부 URL) 방식: {@code submissionUrl}
 * 둘 중 하나는 반드시 포함되어야 한다.
 */
public record SubmitWorkRequest(
        String submissionUrl,
        String videoFileKey,
        String videoContentType,
        Long videoSizeBytes
) {}
