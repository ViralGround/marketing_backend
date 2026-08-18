package com.viralground.backend.config;

import com.viralground.backend.entity.Member;
import com.viralground.backend.entity.MemberStatus;
import com.viralground.backend.entity.Role;
import com.viralground.backend.repository.CompanyProfileRepository;
import com.viralground.backend.repository.CreatorProfileRepository;
import com.viralground.backend.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoAccountBootstrapTest {

    private final MemberRepository members = mock(MemberRepository.class);
    private final CreatorProfileRepository creators = mock(CreatorProfileRepository.class);
    private final CompanyProfileRepository companies = mock(CompanyProfileRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);

    @Test
    void createsApprovedCreatorAndCompanyWithProfiles() throws Exception {
        when(members.existsByEmail(any())).thenReturn(false);
        when(encoder.encode("DemoLogin!2026")).thenReturn("encoded");
        AtomicInteger ids = new AtomicInteger();
        when(members.save(any(Member.class))).thenAnswer(invocation -> {
            Member member = invocation.getArgument(0);
            member.setId(ids.incrementAndGet());
            assertEquals(MemberStatus.APPROVED, member.getStatus());
            return member;
        });

        bootstrap("test", "DemoLogin!2026").run();

        verify(members).existsByEmail("creator.demo@viralground.local");
        verify(members).existsByEmail("company.demo@viralground.local");
        verify(creators).save(any());
        verify(companies).save(any());
        verify(members).save(org.mockito.ArgumentMatchers.argThat(member -> member.getRole() == Role.CREATOR));
        verify(members).save(org.mockito.ArgumentMatchers.argThat(member -> member.getRole() == Role.COMPANY));
    }

    @Test
    void refusesProductionBeforeWritingAccounts() {
        assertThrows(IllegalStateException.class,
                () -> bootstrap("production", "DemoLogin!2026").run());

        verify(members, never()).save(any());
    }

    @Test
    void requiresAnExplicitStrongEnoughDemoPassword() {
        assertThrows(IllegalStateException.class, () -> bootstrap("test", "short").run());
        verify(members, never()).save(any());
    }

    private DemoAccountBootstrap bootstrap(String environment, String password) {
        return new DemoAccountBootstrap(
                members,
                creators,
                companies,
                encoder,
                environment,
                password,
                "creator.demo@viralground.local",
                "company.demo@viralground.local");
    }
}
