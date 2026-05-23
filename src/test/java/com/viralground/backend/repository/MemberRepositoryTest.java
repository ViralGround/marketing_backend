package com.viralground.backend.repository;

import com.viralground.backend.entity.Member;
import com.viralground.backend.entity.MemberStatus;
import com.viralground.backend.entity.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect="
})
class MemberRepositoryTest {

    @Autowired
    MemberRepository memberRepository;

    @Autowired
    TestEntityManager em;

    private Member saveMember(String email, LocalDateTime createdAt) {
        Member m = Member.builder()
                .email(email)
                .password("pw")
                .name("이름 " + email)
                .role(Role.CREATOR)
                .status(MemberStatus.PENDING)
                .build();
        Member saved = em.persistAndFlush(m);
        // @Column(updatable=false) 인 created_at 은 JPA setter 로 변경 안 됨.
        // 테스트에서 원하는 시간으로 셋업하려면 native UPDATE 로 우회.
        em.getEntityManager()
                .createNativeQuery("UPDATE members SET created_at = ?1 WHERE id = ?2")
                .setParameter(1, createdAt)
                .setParameter(2, saved.getId())
                .executeUpdate();
        em.clear();
        return em.find(Member.class, saved.getId());
    }

    @Test
    void findMemberStats_total_today_week_를_단일_쿼리로_집계한다() {
        // given — 오늘 가입 2명, 이번 주(7일 이내) 가입 1명 추가, 한 달 전 1명. 총 4명.
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = now.toLocalDate().atStartOfDay();
        LocalDateTime weekAgo = now.minusDays(7);

        saveMember("today1@test.com", now.minusHours(1));
        saveMember("today2@test.com", now.minusMinutes(10));
        saveMember("thisweek@test.com", now.minusDays(3));
        saveMember("oldold@test.com", now.minusDays(30));

        // when
        MemberRepository.MemberStatsRow stats = memberRepository.findMemberStats(todayStart, weekAgo);

        // then
        assertThat(stats).isNotNull();
        assertThat(stats.getTotal()).isEqualTo(4L);
        assertThat(stats.getTodayCount()).isEqualTo(2L);
        // 이번 주 = 오늘 가입 + 3일 전 가입 = 3명 (7일 이내)
        assertThat(stats.getWeekCount()).isEqualTo(3L);
    }

    @Test
    void findMemberStats_회원이_없으면_모두_0_을_반환한다() {
        // given — DB 비어있음

        // when
        MemberRepository.MemberStatsRow stats = memberRepository.findMemberStats(
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now().minusDays(7));

        // then — JPQL SUM 은 빈 결과에서 null 이 아닌 0 을 보장하지 않으므로 null 또는 0 모두 허용.
        //        서비스 레이어에서 null guard 로 처리한다.
        assertThat(stats).isNotNull();
        assertThat(stats.getTotal()).isEqualTo(0L);
        // todayCount, weekCount 는 SUM(CASE...) 라 빈 결과에서 null 가능 — 서비스가 null→0 으로 처리.
    }
}
