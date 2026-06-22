package com.viralground.backend.entity;

/**
 * 릴스 지표 스냅샷의 출처.
 * AUTO  — 인스타 연동(Phyllo) 자동 동기화로 수집.
 * MANUAL — 크리에이터/관리자가 수동 입력(SubmissionMetric 폴백).
 */
public enum MetricSource {
    AUTO, MANUAL
}
