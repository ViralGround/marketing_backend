package com.viralground.backend.service;

import com.viralground.backend.entity.*;
import com.viralground.backend.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminServiceGetMembersTest {

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

    @InjectMocks
    AdminService adminService;

    private Member creator(Integer id, Boolean emailVerified) {
        return Member.builder()
                .id(id)
                .email("creator" + id + "@vg.test")
                .password("pw")
                .name("크리에이터" + id)
                .role(Role.CREATOR)
                .status(MemberStatus.PENDING)
                .emailVerified(emailVerified)
                .build();
    }

    @Test
    void emailVerified_가_null_인_회원이_있어도_목록을_반환한다() {
        // given — 레거시/시드 데이터로 emailVerified 가 null 인 레코드가 존재할 수 있다.
        //        기존 구현은 Map.of(...) 로 인해 NPE 를 던지며 500 을 유발했다.
        Member legacy = creator(1, null);
        legacy.setCreatedAt(LocalDateTime.now());
        when(memberRepository.findAllByStatusAndSearch(null, null))
                .thenReturn(List.of(legacy));
        when(profileRepository.findByMemberIdIn(List.of(1))).thenReturn(List.of());

        // when & then
        assertThatCode(() -> adminService.getMembers("ALL", null))
                .doesNotThrowAnyException();

        Map<String, Object> result = adminService.getMembers("ALL", null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members = (List<Map<String, Object>>) result.get("members");
        assertThat(members).hasSize(1);
        assertThat(members.get(0)).containsEntry("emailVerified", null);
    }

    @Test
    void CREATOR_회원의_instagramId_가_응답에_포함된다() {
        // given
        Member m = creator(7, true);
        m.setCreatedAt(LocalDateTime.now());
        CreatorProfile profile = CreatorProfile.builder()
                .memberId(7)
                .instagramId("viral_gildong")
                .build();
        when(memberRepository.findAllByStatusAndSearch(null, null))
                .thenReturn(List.of(m));
        when(profileRepository.findByMemberIdIn(List.of(7))).thenReturn(List.of(profile));

        // when
        Map<String, Object> result = adminService.getMembers("ALL", null);

        // then
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members = (List<Map<String, Object>>) result.get("members");
        assertThat(members).hasSize(1);
        assertThat(members.get(0)).containsEntry("instagramId", "viral_gildong");
    }

    @Test
    void CREATOR_프로필이_없으면_instagramId_는_null_이다() {
        // given — 가입 직후이거나 프로필 생성에 실패한 에지 케이스에서도 목록 자체는 정상 반환해야 한다.
        Member m = creator(9, true);
        m.setCreatedAt(LocalDateTime.now());
        when(memberRepository.findAllByStatusAndSearch(null, null))
                .thenReturn(List.of(m));
        when(profileRepository.findByMemberIdIn(List.of(9))).thenReturn(List.of());

        // when
        Map<String, Object> result = adminService.getMembers("ALL", null);

        // then
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members = (List<Map<String, Object>>) result.get("members");
        assertThat(members.get(0)).containsKey("instagramId");
        assertThat(members.get(0).get("instagramId")).isNull();
    }

    @Test
    void COMPANY_회원은_profileRepository_를_조회하지_않는다() {
        // given — COMPANY 는 CreatorProfile 이 애초에 없으므로 IN 쿼리 자체를 호출하지 말아야 한다.
        Member company = Member.builder()
                .id(3)
                .email("co@vg.test")
                .password("pw")
                .name("회사")
                .role(Role.COMPANY)
                .status(MemberStatus.APPROVED)
                .emailVerified(true)
                .build();
        company.setCreatedAt(LocalDateTime.now());
        when(memberRepository.findAllByStatusAndSearch(null, null))
                .thenReturn(List.of(company));

        // when
        Map<String, Object> result = adminService.getMembers("ALL", null);

        // then
        verify(profileRepository, never()).findByMemberIdIn(anyCollection());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members = (List<Map<String, Object>>) result.get("members");
        assertThat(members.get(0)).containsEntry("instagramId", null);
    }

    @Test
    void 다수의_CREATOR_회원_조회시_profile_조회는_한_번만_일어난다() {
        // given — N+1 회귀 방지. CREATOR 가 여러 명이어도 findByMemberIdIn 한 번에 끝나야 한다.
        Member m1 = creator(1, true);
        Member m2 = creator(2, true);
        Member m3 = creator(3, true);
        m1.setCreatedAt(LocalDateTime.now());
        m2.setCreatedAt(LocalDateTime.now());
        m3.setCreatedAt(LocalDateTime.now());
        CreatorProfile p1 = CreatorProfile.builder().memberId(1).instagramId("ig1").build();
        CreatorProfile p2 = CreatorProfile.builder().memberId(2).instagramId("ig2").build();
        when(memberRepository.findAllByStatusAndSearch(null, null))
                .thenReturn(List.of(m1, m2, m3));
        when(profileRepository.findByMemberIdIn(List.of(1, 2, 3)))
                .thenReturn(List.of(p1, p2));

        // when
        adminService.getMembers("ALL", null);

        // then — IN 쿼리 1회만, 개별 findByMemberId 는 0회.
        verify(profileRepository, org.mockito.Mockito.times(1)).findByMemberIdIn(anyCollection());
        verify(profileRepository, never()).findByMemberId(org.mockito.ArgumentMatchers.anyInt());
    }
}
