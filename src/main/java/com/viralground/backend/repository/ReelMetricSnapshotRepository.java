package com.viralground.backend.repository;

import com.viralground.backend.entity.ReelMetricSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReelMetricSnapshotRepository extends JpaRepository<ReelMetricSnapshot, Integer> {

    /** application 의 가장 최근 스냅샷 — 대시보드 집계용. */
    Optional<ReelMetricSnapshot> findTopByApplicationIdOrderByCapturedAtDesc(Integer applicationId);

    /** application 의 스냅샷 시계열(오래된→최신) — 조회수 증가분(추이) 계산용. */
    List<ReelMetricSnapshot> findByApplicationIdOrderByCapturedAtAsc(Integer applicationId);

    /** 여러 application 의 스냅샷 일괄 조회 — 서비스에서 application 별 최신/시계열로 정리. */
    List<ReelMetricSnapshot> findByApplicationIdIn(List<Integer> applicationIds);
}
