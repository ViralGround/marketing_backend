package com.viralground.backend.instagram;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 인스타그램 API 연동 전 목 구현. 릴스 URL 로 시드를 고정해 결정적인 가짜 지표를 만든다.
 * (같은 URL → 항상 같은 수치라 새로고침해도 대시보드가 흔들리지 않는다.)
 * 실제 연동 시 이 빈을 실제 API 구현으로 교체. (Math.random 대신 시드 고정 Random 사용.)
 */
@Component
public class MockInstagramMetricsProvider implements InstagramMetricsProvider {

    private static final int TREND_DAYS = 14;

    @Override
    public ReelMetrics fetch(String reelUrl) {
        long seed = reelUrl == null ? 0L : reelUrl.hashCode();
        Random r = new Random(seed);

        long views = 30_000L + (long) (r.nextDouble() * 2_970_000L);      // 3만 ~ 300만
        long likes = (long) (views * (0.03 + r.nextDouble() * 0.05));      // 3 ~ 8%
        long comments = (long) (views * (0.003 + r.nextDouble() * 0.012)); // 0.3 ~ 1.5%
        long shares = (long) (likes * (0.05 + r.nextDouble() * 0.10));      // 좋아요의 5 ~ 15%

        // 최근 14일 일별 조회수 — 게시 직후 높고 점차 감소하는 듯한 패턴.
        List<Long> daily = new ArrayList<>(TREND_DAYS);
        for (int i = 0; i < TREND_DAYS; i++) {
            double factor = 0.4 + r.nextDouble() * 1.2;
            daily.add(Math.max(0L, (long) (views / 40.0 * factor)));
        }

        return new ReelMetrics(views, likes, comments, shares, daily);
    }
}
