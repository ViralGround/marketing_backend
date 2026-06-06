package com.viralground.backend.repository;

import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.CampaignStatus;
import com.viralground.backend.entity.EscrowStatus;
import com.viralground.backend.entity.Member;
import com.viralground.backend.entity.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect="
})
class CampaignRepositoryFeaturedTest {

    @Autowired
    CampaignRepository campaignRepository;

    @Autowired
    TestEntityManager em;

    Integer creatorId;

    @BeforeEach
    void setUp() {
        Member m = em.persistAndFlush(Member.builder()
                .email("company@test.com")
                .password("x")
                .name("기업")
                .role(Role.COMPANY)
                .build());
        creatorId = m.getId();
    }

    private Campaign save(String title, CampaignStatus status, Integer featuredOrder,
                          LocalDateTime hiddenAt, LocalDateTime deadline, Integer ownerId) {
        Campaign c = Campaign.builder()
                .title(title)
                .description("desc")
                .brandName("Brand")
                .rewardAmount(10_000)
                .totalBudget(50_000)
                .escrowStatus(EscrowStatus.NONE)
                .maxParticipants(5)
                .status(status)
                .createdById(ownerId)
                .featuredOrder(featuredOrder)
                .hiddenAt(hiddenAt)
                .deadline(deadline)
                .build();
        return campaignRepository.save(c);
    }

    @Test
    void findFeaturedOpen_featuredOrder_오름차순으로_반환() {
        // given — featured 3건을 순번 역순으로 저장
        save("세번째", CampaignStatus.OPEN, 3, null, null, creatorId);
        save("첫번째", CampaignStatus.OPEN, 1, null, null, creatorId);
        save("두번째", CampaignStatus.OPEN, 2, null, null, creatorId);

        // when
        List<Campaign> result = campaignRepository.findFeaturedOpen(LocalDateTime.now());

        // then
        assertThat(result).extracting(Campaign::getTitle)
                .containsExactly("첫번째", "두번째", "세번째");
    }

    @Test
    void findFeaturedOpen_비대표_OPEN_캠페인은_제외() {
        // given — featuredOrder 가 null 이면 노출 대상 아님
        save("대표", CampaignStatus.OPEN, 1, null, null, creatorId);
        save("비대표", CampaignStatus.OPEN, null, null, null, creatorId);

        // when
        List<Campaign> result = campaignRepository.findFeaturedOpen(LocalDateTime.now());

        // then
        assertThat(result).extracting(Campaign::getTitle).containsExactly("대표");
    }

    @Test
    void findFeaturedOpen_숨김_마감_DRAFT_는_제외() {
        // given — featured 지정됐어도 숨김/마감/비OPEN 이면 랜딩 노출 X
        LocalDateTime now = LocalDateTime.now();
        save("정상", CampaignStatus.OPEN, 1, null, now.plusDays(2), creatorId);
        save("숨김", CampaignStatus.OPEN, 2, now, null, creatorId);
        save("마감", CampaignStatus.OPEN, 3, null, now.minusDays(1), creatorId);
        save("DRAFT", CampaignStatus.DRAFT, 4, null, null, creatorId);

        // when
        List<Campaign> result = campaignRepository.findFeaturedOpen(now);

        // then
        assertThat(result).extracting(Campaign::getTitle).containsExactly("정상");
    }

    @Test
    void countFeaturedOpen_노출_가능한_대표만_센다() {
        // given — 숨김/마감된 대표는 한도를 소모하지 않아야 한다
        LocalDateTime now = LocalDateTime.now();
        save("노출", CampaignStatus.OPEN, 1, null, null, creatorId);
        save("숨김", CampaignStatus.OPEN, 2, now, null, creatorId);
        save("마감", CampaignStatus.OPEN, 3, null, now.minusDays(1), creatorId);
        save("비대표", CampaignStatus.OPEN, null, null, null, creatorId);

        // when & then
        assertThat(campaignRepository.countFeaturedOpen(now)).isEqualTo(1);
    }

    @Test
    void maxFeaturedOrder_기존_최대_순번_반환_없으면_0() {
        // given — 대표가 없으면 0, 중간이 비어도 최대값
        assertThat(campaignRepository.maxFeaturedOrder()).isZero();
        save("대표1", CampaignStatus.OPEN, 1, null, null, creatorId);
        save("대표3", CampaignStatus.OPEN, 3, null, null, creatorId);
        save("비대표", CampaignStatus.OPEN, null, null, null, creatorId);

        // when & then
        assertThat(campaignRepository.maxFeaturedOrder()).isEqualTo(3);
    }

    @Test
    void findOpenByCreator_해당_기업의_OPEN_캠페인만_반환() {
        // given — 다른 기업 캠페인은 제외
        Member other = em.persistAndFlush(Member.builder()
                .email("other@test.com").password("x").name("타사").role(Role.COMPANY).build());
        LocalDateTime now = LocalDateTime.now();
        save("내캠페인", CampaignStatus.OPEN, null, null, null, creatorId);
        save("내마감", CampaignStatus.OPEN, null, null, now.minusDays(1), creatorId);
        save("타사캠페인", CampaignStatus.OPEN, null, null, null, other.getId());

        // when
        List<Campaign> result = campaignRepository.findOpenByCreator(creatorId, now);

        // then
        assertThat(result).extracting(Campaign::getTitle).containsExactly("내캠페인");
    }
}
