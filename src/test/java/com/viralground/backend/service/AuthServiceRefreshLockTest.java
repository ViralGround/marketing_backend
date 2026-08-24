package com.viralground.backend.service;

import com.viralground.backend.entity.Member;
import com.viralground.backend.entity.MemberStatus;
import com.viralground.backend.entity.RefreshToken;
import com.viralground.backend.entity.Role;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.CompanyProfileRepository;
import com.viralground.backend.repository.CreatorProfileRepository;
import com.viralground.backend.repository.MemberRepository;
import com.viralground.backend.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceRefreshLockTest {

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final JwtService jwtService = new JwtService(
            "test-secret-key-at-least-32-characters-long", 60, 120,
            "viralground-api", "viralground-web");
    private AuthService service;
    private Member member;

    @BeforeEach
    void setUp() {
        service = new AuthService(
                memberRepository,
                mock(CreatorProfileRepository.class),
                mock(CompanyProfileRepository.class),
                mock(PasswordEncoder.class),
                jwtService,
                mock(EmailVerificationService.class),
                eventPublisher,
                refreshTokenRepository,
                mock(LegalConsentService.class));
        member = Member.builder()
                .id(7).email("creator@example.test").password("hash").name("Creator")
                .role(Role.CREATOR).status(MemberStatus.APPROVED).emailVerified(true)
                .build();
    }

    @Test
    void refreshLoadsTokenWithDatabaseWriteLockBeforeRotation() {
        var issued = jwtService.generateRefreshToken(member, "family-1");
        RefreshToken stored = new RefreshToken(
                issued.tokenId(), member.getId(), issued.familyId(), issued.expiresAt());
        when(memberRepository.findByIdForUpdate(7)).thenReturn(Optional.of(member));
        when(refreshTokenRepository.findAllByFamilyIdForUpdate("family-1")).thenReturn(List.of(stored));

        var response = service.refresh(issued.token());

        verify(memberRepository).findByIdForUpdate(7);
        verify(refreshTokenRepository).findAllByFamilyIdForUpdate("family-1");
        assertThat(stored.getRevokedAt()).isNotNull();
        assertThat(stored.getReplacedBy()).isNotBlank();
        assertThat(jwtService.hasCurrentAuthVersion(
                jwtService.parseToken(response.getAccessToken()), member)).isTrue();
    }

    @Test
    void refreshRejectsOldAuthVersionAndRevokesTokenFamily() {
        var issued = jwtService.generateRefreshToken(member, "family-1");
        RefreshToken stored = new RefreshToken(
                issued.tokenId(), member.getId(), issued.familyId(), issued.expiresAt());
        member.incrementAuthVersion();
        when(memberRepository.findByIdForUpdate(7)).thenReturn(Optional.of(member));
        when(refreshTokenRepository.findAllByFamilyIdForUpdate("family-1")).thenReturn(List.of(stored));

        assertThatThrownBy(() -> service.refresh(issued.token()))
                .isInstanceOf(AppException.class)
                .extracting(error -> ((AppException) error).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);

        assertThat(stored.getRevokedAt()).isNotNull();
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    @Test
    void logoutLocksMemberThenRevokesTheEntireRefreshFamily() {
        var issued = jwtService.generateRefreshToken(member, "family-1");
        RefreshToken presented = new RefreshToken(
                issued.tokenId(), member.getId(), issued.familyId(), issued.expiresAt());
        RefreshToken rotatedChild = new RefreshToken(
                "child-token-id", member.getId(), issued.familyId(), issued.expiresAt());
        when(memberRepository.findByIdForUpdate(7)).thenReturn(Optional.of(member));
        when(refreshTokenRepository.findAllByFamilyIdForUpdate("family-1"))
                .thenReturn(List.of(presented, rotatedChild));

        service.logout(issued.token());

        verify(memberRepository).findByIdForUpdate(7);
        verify(refreshTokenRepository).findAllByFamilyIdForUpdate("family-1");
        assertThat(presented.getRevokedAt()).isNotNull();
        assertThat(rotatedChild.getRevokedAt()).isNotNull();
        verify(eventPublisher).publishEvent(any(com.viralground.backend.logging.AuditEvent.class));
    }
}
