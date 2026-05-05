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
class CampaignRepositoryTest {

    @Autowired
    CampaignRepository campaignRepository;

    @Autowired
    TestEntityManager em;

    Integer creatorId;

    @BeforeEach
    void setUp() {
        Member m = em.persistAndFlush(Member.builder()
                .email("creator@test.com")
                .password("x")
                .name("크리에이터")
                .role(Role.COMPANY)
                .build());
        creatorId = m.getId();
    }

    private Campaign saveOpenCampaign(String title, String brand, LocalDateTime hiddenAt) {
        Campaign c = Campaign.builder()
                .title(title)
                .description("desc")
                .brandName(brand)
                .rewardAmount(10_000)
                .totalBudget(50_000)
                .escrowStatus(EscrowStatus.NONE)
                .maxParticipants(5)
                .status(CampaignStatus.OPEN)
                .createdById(creatorId)
                .hiddenAt(hiddenAt)
                .build();
        return campaignRepository.save(c);
    }

    @Test
    void findOpenCampaignsAll_숨김_캠페인_제외() {
        // given — OPEN 캠페인 2건 중 1건 숨김 처리
        saveOpenCampaign("노출 캠페인", "BrandA", null);
        saveOpenCampaign("숨김 캠페인", "BrandB", LocalDateTime.now());

        // when
        List<Campaign> result = campaignRepository.findOpenCampaignsAll();

        // then
        assertThat(result).hasSize(1)
                .extracting(Campaign::getTitle)
                .containsExactly("노출 캠페인");
    }

    @Test
    void findOpenCampaignsWithSearch_숨김_캠페인_제외() {
        // given — 같은 검색어에 매치되는 OPEN 캠페인 2건 중 1건 숨김
        saveOpenCampaign("바이럴 그라운드 노출", "Brand", null);
        saveOpenCampaign("바이럴 그라운드 숨김", "Brand", LocalDateTime.now());

        // when
        List<Campaign> result = campaignRepository.findOpenCampaignsWithSearch("바이럴");

        // then
        assertThat(result).hasSize(1)
                .extracting(Campaign::getTitle)
                .containsExactly("바이럴 그라운드 노출");
    }

    @Test
    void findAllByStatus_관리자_쿼리는_숨김_포함() {
        // given — 관리자는 숨김 캠페인까지 봐야 한다 (회귀 가드)
        saveOpenCampaign("노출", "A", null);
        saveOpenCampaign("숨김", "B", LocalDateTime.now());

        // when
        List<Campaign> result = campaignRepository.findAllByStatus(CampaignStatus.OPEN);

        // then
        assertThat(result).hasSize(2)
                .extracting(Campaign::getTitle)
                .containsExactlyInAnyOrder("노출", "숨김");
    }
}
