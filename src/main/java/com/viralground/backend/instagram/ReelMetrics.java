package com.viralground.backend.instagram;

import java.util.List;

/**
 * 릴스 1건의 지표 스냅샷. {@link InstagramMetricsProvider} 가 반환한다.
 *
 * @param views      누적 조회수
 * @param likes      좋아요 수
 * @param comments   댓글 수
 * @param shares     공유 수
 * @param dailyViews 최근 N일(현재 14일) 일별 조회수. 오래된 날 → 최신 날 순서. 추이 차트용.
 */
public record ReelMetrics(long views, long likes, long comments, long shares, List<Long> dailyViews) {}
