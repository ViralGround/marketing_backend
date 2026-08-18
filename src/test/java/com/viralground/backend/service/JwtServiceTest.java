package com.viralground.backend.service;

import com.viralground.backend.entity.Member;
import com.viralground.backend.entity.MemberStatus;
import com.viralground.backend.entity.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {
    private JwtService service;
    private Member member;

    @BeforeEach
    void setUp() {
        service = new JwtService("test-secret-key-at-least-32-characters-long", 60, 120,
                "viralground-api", "viralground-web");
        member = Member.builder().id(7).email("creator@example.com").name("Creator")
                .role(Role.CREATOR).status(MemberStatus.APPROVED).emailVerified(true).build();
    }

    @Test
    void accessTokenHasExplicitTypeIssuerAndAudience() {
        var claims = service.parseToken(service.generateAccessToken(member));
        assertThat(service.isTokenType(claims, "access")).isTrue();
        assertThat(claims.getIssuer()).isEqualTo("viralground-api");
        assertThat(claims.getAudience()).contains("viralground-web");
        assertThat(claims.getId()).isNotBlank();
        assertThat(claims.get("role", String.class)).isEqualTo("CREATOR");
        assertThat(claims).doesNotContainKeys("email", "name");
    }

    @Test
    void refreshTokenCarriesRotationIdentifiers() {
        var issued = service.generateRefreshToken(member, "family-1");
        var claims = service.parseToken(issued.token());
        assertThat(service.isTokenType(claims, "refresh")).isTrue();
        assertThat(claims.getId()).isEqualTo(issued.tokenId());
        assertThat(claims.get("family_id", String.class)).isEqualTo("family-1");
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() {
        JwtService other = new JwtService("test-secret-key-at-least-32-characters-long", 60, 120,
                "other", "viralground-web");
        assertThat(service.parseToken(other.generateAccessToken(member))).isNull();
    }
}
