package com.viralground.backend.repository;

import com.viralground.backend.entity.MetricSource;
import com.viralground.backend.entity.ReelMetricSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect="
})
class ReelMetricSnapshotRepositoryTest {

    @Autowired
    ReelMetricSnapshotRepository repository;

    private ReelMetricSnapshot snapshot(int applicationId, long views, LocalDateTime capturedAt) {
        return ReelMetricSnapshot.builder()
                .applicationId(applicationId)
                .views(views).likes(views / 10).comments(views / 100).shares(views / 200)
                .source(MetricSource.AUTO)
                .capturedAt(capturedAt)
                .build();
    }

    @Test
    void application_의_최신_스냅샷을_조회한다() {
        // given — 같은 application 의 스냅샷 3건 (시점 다름)
        LocalDateTime base = LocalDateTime.of(2026, 6, 1, 0, 0);
        repository.save(snapshot(10, 1000, base));
        repository.save(snapshot(10, 2000, base.plusDays(1)));
        repository.save(snapshot(10, 3000, base.plusDays(2)));
        repository.save(snapshot(11, 999, base.plusDays(5))); // 다른 application

        // when
        Optional<ReelMetricSnapshot> latest =
                repository.findTopByApplicationIdOrderByCapturedAtDesc(10);

        // then — 가장 최근(views=3000)
        assertThat(latest).isPresent();
        assertThat(latest.get().getViews()).isEqualTo(3000);
    }

    @Test
    void application_의_스냅샷_시계열을_오래된순으로_조회한다() {
        // given
        LocalDateTime base = LocalDateTime.of(2026, 6, 1, 0, 0);
        repository.save(snapshot(20, 3000, base.plusDays(2)));
        repository.save(snapshot(20, 1000, base));
        repository.save(snapshot(20, 2000, base.plusDays(1)));

        // when — 추이(delta) 계산용 오름차순
        List<ReelMetricSnapshot> series =
                repository.findByApplicationIdOrderByCapturedAtAsc(20);

        // then
        assertThat(series).hasSize(3)
                .extracting(ReelMetricSnapshot::getViews)
                .containsExactly(1000L, 2000L, 3000L);
    }
}
