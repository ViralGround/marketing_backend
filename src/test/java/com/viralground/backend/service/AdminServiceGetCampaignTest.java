package com.viralground.backend.service;

import com.viralground.backend.dto.admin.CampaignDetailResponse;
import com.viralground.backend.entity.*;
import com.viralground.backend.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminServiceGetCampaignTest {

    @Mock MemberRepository memberRepository;
    @Mock CreatorProfileRepository profileRepository;
    @Mock CompanyProfileRepository companyProfileRepository;
    @Mock CampaignRepository campaignRepository;
    @Mock CampaignApplicationRepository applicationRepository;
    @Mock EscrowTransactionRepository escrowTransactionRepository;
    @Mock EmailService emailService;
    @Mock EscrowService escrowService;
    @Mock ApplicationSubmissionRepository submissionRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock com.viralground.backend.storage.FileStorage fileStorage;

    @InjectMocks
    AdminService adminService;

    @Test
    void getCampaign_은_readOnly_트랜잭션으로_감싸져야_한다() throws NoSuchMethodException {
        // given — open-in-view=false 환경에서 CampaignApplication.creator 는 LAZY 프록시다.
        //        CampaignDetailResponse 생성자가 creator 를 역참조하므로 서비스 메서드가
        //        @Transactional 로 감싸져 세션이 유지되어야 LazyInitializationException 이 안 난다.
        Method method = AdminService.class.getDeclaredMethod("getCampaign", Integer.class);

        // when
        Transactional tx = method.getAnnotation(Transactional.class);

        // then
        assertThat(tx)
                .as("getCampaign 은 Hibernate 세션 유지를 위해 @Transactional 이 필요하다")
                .isNotNull();
        assertThat(tx.readOnly())
                .as("단순 조회이므로 readOnly=true 로 두어 flush 비용을 제거한다")
                .isTrue();
    }

    @Test
    void 지원자가_있는_캠페인을_조회하면_신청과_크리에이터_정보가_포함된다() {
        // given
        Campaign campaign = Campaign.builder()
                .id(5)
                .title("캠페인 5")
                .status(CampaignStatus.OPEN)
                .escrowStatus(EscrowStatus.FUNDED)
                .rewardAmount(10_000)
                .maxParticipants(3)
                .totalBudget(30_000)
                .build();
        Member creator = Member.builder()
                .id(20)
                .email("c20@vg.test")
                .password("pw")
                .name("크리에이터20")
                .role(Role.CREATOR)
                .status(MemberStatus.APPROVED)
                .emailVerified(true)
                .build();
        CampaignApplication app = CampaignApplication.builder()
                .id(100)
                .campaignId(5)
                .creatorId(20)
                .status(ApplicationStatus.PENDING)
                .build();
        app.setCreator(creator);

        when(campaignRepository.findById(5)).thenReturn(Optional.of(campaign));
        when(applicationRepository.findByCampaignIdOrderByAppliedAtDesc(5))
                .thenReturn(List.of(app));
        when(submissionRepository.findByApplicationIdInOrderByApplicationIdAscSubmittedAtAsc(List.of(100)))
                .thenReturn(List.of());

        // when
        CampaignDetailResponse response = adminService.getCampaign(5);

        // then
        assertThat(response.getId()).isEqualTo(5);
        assertThat(response.getApplications()).hasSize(1);
        CampaignDetailResponse.ApplicationInfo info = response.getApplications().get(0);
        assertThat(info.creator()).isNotNull();
        assertThat(info.creator().name()).isEqualTo("크리에이터20");
    }

    @Test
    void 캠페인_목록_조회시_신청자_수는_IN_쿼리_한_번으로_집계된다() {
        // given — N+1 회귀 방지. 캠페인이 여러 개여도 countByCampaignIdIn 한 번에 신청 수를 받아와야 한다.
        Campaign c1 = Campaign.builder().id(1).title("A").status(CampaignStatus.OPEN)
                .escrowStatus(EscrowStatus.FUNDED).rewardAmount(1).maxParticipants(1).totalBudget(1).build();
        Campaign c2 = Campaign.builder().id(2).title("B").status(CampaignStatus.OPEN)
                .escrowStatus(EscrowStatus.FUNDED).rewardAmount(1).maxParticipants(1).totalBudget(1).build();
        Campaign c3 = Campaign.builder().id(3).title("C").status(CampaignStatus.OPEN)
                .escrowStatus(EscrowStatus.FUNDED).rewardAmount(1).maxParticipants(1).totalBudget(1).build();
        when(campaignRepository.findAllByStatus(CampaignStatus.OPEN))
                .thenReturn(List.of(c1, c2, c3));

        CampaignApplicationRepository.CampaignCountRow row1 = countRow(1, 5L);
        CampaignApplicationRepository.CampaignCountRow row3 = countRow(3, 2L);
        when(applicationRepository.countByCampaignIdIn(List.of(1, 2, 3)))
                .thenReturn(List.of(row1, row3));

        // when
        List<Map<String, Object>> result = adminService.getCampaigns("OPEN");

        // then
        assertThat(result).hasSize(3);
        Map<String, Object> r1 = result.stream().filter(m -> m.get("id").equals(1)).findFirst().orElseThrow();
        Map<String, Object> r2 = result.stream().filter(m -> m.get("id").equals(2)).findFirst().orElseThrow();
        Map<String, Object> r3 = result.stream().filter(m -> m.get("id").equals(3)).findFirst().orElseThrow();
        assertThat(r1).containsEntry("applicationCount", 5);
        assertThat(r2).containsEntry("applicationCount", 0);
        assertThat(r3).containsEntry("applicationCount", 2);

        verify(applicationRepository, times(1)).countByCampaignIdIn(anyList());
        verify(applicationRepository, never())
                .findByCampaignIdOrderByAppliedAtDesc(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void getCampaign_은_브랜드_소개와_로고URL을_담는다() {
        // given — 관리자 직접 생성 캠페인의 브랜드 소개·로고
        Campaign campaign = Campaign.builder()
                .id(5).title("c").status(CampaignStatus.OPEN).escrowStatus(EscrowStatus.FUNDED)
                .rewardAmount(1).maxParticipants(1).totalBudget(1)
                .brandIntroduction("브랜드 소개").brandLogoFileKey("thumbnails/brand-5").build();
        when(campaignRepository.findById(5)).thenReturn(Optional.of(campaign));
        when(applicationRepository.findByCampaignIdOrderByAppliedAtDesc(5)).thenReturn(List.of());
        when(fileStorage.signedDownloadUrl("thumbnails/brand-5")).thenReturn("https://signed/brand-5");

        // when
        CampaignDetailResponse response = adminService.getCampaign(5);

        // then
        assertThat(response.getBrandIntroduction()).isEqualTo("브랜드 소개");
        assertThat(response.getBrandLogoUrl()).isEqualTo("https://signed/brand-5");
    }

    private CampaignApplicationRepository.CampaignCountRow countRow(int campaignId, long count) {
        return new CampaignApplicationRepository.CampaignCountRow() {
            @Override public Integer getCampaignId() { return campaignId; }
            @Override public Long getCount() { return count; }
        };
    }
}
