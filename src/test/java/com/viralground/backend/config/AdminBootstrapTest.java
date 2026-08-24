package com.viralground.backend.config;

import com.viralground.backend.entity.Member;
import com.viralground.backend.entity.MemberStatus;
import com.viralground.backend.entity.Role;
import com.viralground.backend.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminBootstrapTest {
    private final MemberRepository members = mock(MemberRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);

    @Test
    void beanRequiresTheExplicitEnabledSwitch() {
        ConditionalOnProperty condition =
                AdminBootstrap.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(condition).isNotNull();
        assertThat(condition.prefix()).isEqualTo("admin.bootstrap");
        assertThat(condition.name()).containsExactly("enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isFalse();
    }

    @Test
    void enabledBootstrapNeverSilentlyAcceptsBlankCredentials() {
        AdminBootstrap bootstrap = new AdminBootstrap(members, encoder, "", "", "");

        assertThatThrownBy(bootstrap::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires email, password, and name");
        verify(members, never()).save(any());
    }

    @Test
    void createsOnlyAnApprovedVerifiedAdmin() throws Exception {
        when(members.findByEmail("qa-admin@viralground.kr")).thenReturn(Optional.empty());
        when(encoder.encode("StrongOneShotSecret!2026")).thenReturn("encoded");
        when(members.save(any(Member.class))).thenAnswer(invocation -> {
            Member member = invocation.getArgument(0);
            member.setId(42);
            return member;
        });
        AdminBootstrap bootstrap = new AdminBootstrap(
                members,
                encoder,
                "QA-Admin@viralground.kr",
                "StrongOneShotSecret!2026",
                "QA Admin");

        assertThatThrownBy(bootstrap::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("process intentionally stopped");

        verify(members).save(org.mockito.ArgumentMatchers.argThat(member ->
                member.getRole() == Role.ADMIN
                        && member.getStatus() == MemberStatus.APPROVED
                        && Boolean.TRUE.equals(member.getEmailVerified())
                        && member.getName().equals("QA Admin")));
    }

    @Test
    void existingEmailMustAlreadyBeAnApprovedVerifiedAdminAndStillStops() {
        Member correct = Member.builder()
                .email("qa-admin@viralground.kr")
                .password("encoded")
                .name("QA Admin")
                .role(Role.ADMIN)
                .status(MemberStatus.APPROVED)
                .emailVerified(true)
                .build();
        when(members.findByEmail("qa-admin@viralground.kr")).thenReturn(Optional.of(correct));
        AdminBootstrap bootstrap = new AdminBootstrap(
                members,
                encoder,
                "qa-admin@viralground.kr",
                "StrongOneShotSecret!2026",
                "QA Admin");

        assertThatThrownBy(bootstrap::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disable bootstrap");
        verify(members, never()).save(any());
    }

    @Test
    void existingNonAdminEmailIsNeverAcceptedAsBootstrapSuccess() {
        Member creator = Member.builder()
                .email("qa-admin@viralground.kr")
                .password("encoded")
                .name("Not Admin")
                .role(Role.CREATOR)
                .status(MemberStatus.APPROVED)
                .emailVerified(true)
                .build();
        when(members.findByEmail("qa-admin@viralground.kr")).thenReturn(Optional.of(creator));
        AdminBootstrap bootstrap = new AdminBootstrap(
                members,
                encoder,
                "qa-admin@viralground.kr",
                "StrongOneShotSecret!2026",
                "QA Admin");

        assertThatThrownBy(bootstrap::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not an approved, verified ADMIN");
        verify(members, never()).save(any());
    }
}
