package com.viralground.backend.dto.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.viralground.backend.entity.CreatorProfile;
import com.viralground.backend.entity.EditingTool;
import com.viralground.backend.entity.Gender;
import com.viralground.backend.entity.Member;
import com.viralground.backend.entity.MemberStatus;
import com.viralground.backend.entity.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class MemberDetailResponseSerializationTest {

    ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private Member member() {
        Member m = Member.builder()
                .id(1)
                .email("c@vg.test")
                .password("pw")
                .name("크리에이터")
                .role(Role.CREATOR)
                .status(MemberStatus.PENDING)
                .emailVerified(true)
                .build();
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }

    private CreatorProfile profile() {
        return CreatorProfile.builder()
                .memberId(1)
                .gender(Gender.FEMALE)
                .age(25)
                .faceExposure(true)
                .editingTool(EditingTool.CAPCUT)
                .instagramId("viral_gildong")
                .build();
    }

    @Test
    void JSON_응답에_creatorProfile_키가_포함된다() throws Exception {
        // given — 관리자 상세 페이지가 member.creatorProfile 을 읽고 설문 섹션을 조건부 렌더한다.
        //        백엔드가 "profile" 로 직렬화하면 섹션이 영구히 숨겨진다.
        MemberDetailResponse response = new MemberDetailResponse(member(), profile(), 0);

        // when
        String json = mapper.writeValueAsString(response);

        // then
        assertThat(json).contains("\"creatorProfile\"");
        assertThat(json).doesNotContain("\"profile\"");
    }

    @Test
    void 프로필이_null_이면_creatorProfile_키가_null_로_직렬화된다() throws Exception {
        // given — COMPANY/ADMIN 회원이거나 프로필이 아직 생성되지 않은 경우.
        MemberDetailResponse response = new MemberDetailResponse(member(), null, 0);

        // when
        String json = mapper.writeValueAsString(response);

        // then
        assertThat(json).contains("\"creatorProfile\":null");
    }
}
