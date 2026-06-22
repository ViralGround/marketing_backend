package com.viralground.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 릴스 1건(application)의 지표 스냅샷. 동기화 잡이 시점마다 한 행씩 적재한다.
 * 대시보드는 application 별 <b>최신 스냅샷</b>을 읽어 집계하고, 연속 스냅샷 간
 * 조회수 증가분으로 추이(viewsTrend)를 구성한다.
 */
@Entity
@Table(name = "reel_metric_snapshots",
        indexes = @Index(name = "idx_snapshot_app_captured",
                columnList = "application_id, captured_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReelMetricSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "application_id", nullable = false)
    private Integer applicationId;

    @Column(nullable = false)
    @Builder.Default
    private Long views = 0L;

    @Column(nullable = false)
    @Builder.Default
    private Long likes = 0L;

    @Column(nullable = false)
    @Builder.Default
    private Long comments = 0L;

    @Column(nullable = false)
    @Builder.Default
    private Long shares = 0L;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private MetricSource source = MetricSource.AUTO;

    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;
}
