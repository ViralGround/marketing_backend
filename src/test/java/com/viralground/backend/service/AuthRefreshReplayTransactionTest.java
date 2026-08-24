package com.viralground.backend.service;

import com.viralground.backend.entity.Member;
import com.viralground.backend.entity.MemberStatus;
import com.viralground.backend.entity.RefreshToken;
import com.viralground.backend.entity.Role;
import com.viralground.backend.dto.auth.TokenResponse;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.MemberRepository;
import com.viralground.backend.repository.RefreshTokenRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AuthRefreshReplayTransactionTest {

    @Autowired AuthService authService;
    @Autowired JwtService jwtService;
    @Autowired MemberRepository memberRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;

    private Integer memberId;
    private String familyId;

    @AfterEach
    void cleanFixture() {
        if (familyId != null) {
            refreshTokenRepository.deleteAll(refreshTokenRepository.findAllByFamilyId(familyId));
        }
        if (memberId != null) {
            memberRepository.deleteById(memberId);
        }
    }

    @Test
    void rejectedRefreshReplayCommitsRevocationOfTheRotatedChildToken() {
        Member member = memberRepository.save(Member.builder()
                .email("refresh-replay-" + UUID.randomUUID() + "@example.test")
                .password("not-used")
                .name("Refresh replay fixture")
                .role(Role.CREATOR)
                .status(MemberStatus.APPROVED)
                .emailVerified(true)
                .build());
        memberId = member.getId();
        familyId = "family-" + UUID.randomUUID();

        JwtService.IssuedRefreshToken original = jwtService.generateRefreshToken(member, familyId);
        refreshTokenRepository.save(new RefreshToken(
                original.tokenId(), member.getId(), familyId, original.expiresAt()));

        var rotated = authService.refresh(original.token());

        assertThatThrownBy(() -> authService.refresh(original.token()))
                .isInstanceOf(AppException.class)
                .extracting(error -> ((AppException) error).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);

        List<RefreshToken> persistedFamily = refreshTokenRepository.findAllByFamilyId(familyId);
        assertThat(persistedFamily).hasSize(2).allMatch(token -> token.getRevokedAt() != null);
        assertThatThrownBy(() -> authService.refresh(rotated.getRefreshToken()))
                .isInstanceOf(AppException.class)
                .extracting(error -> ((AppException) error).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    void oldReplayRacingWithChildRotationLeavesNoLiveDescendant() throws Exception {
        Member member = memberRepository.save(Member.builder()
                .email("refresh-chain-race-" + UUID.randomUUID() + "@example.test")
                .password("not-used")
                .name("Refresh chain race fixture")
                .role(Role.CREATOR)
                .status(MemberStatus.APPROVED)
                .emailVerified(true)
                .build());
        memberId = member.getId();
        familyId = "family-race-" + UUID.randomUUID();

        JwtService.IssuedRefreshToken original = jwtService.generateRefreshToken(member, familyId);
        refreshTokenRepository.save(new RefreshToken(
                original.tokenId(), member.getId(), familyId, original.expiresAt()));
        TokenResponse child = authService.refresh(original.token());

        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var replayFuture = executor.submit(() -> {
                start.await();
                try {
                    authService.refresh(original.token());
                } catch (AppException expectedReplayFailure) {
                    assertThat(expectedReplayFailure.getErrorCode()).isEqualTo(ErrorCode.INVALID_TOKEN);
                }
                return null;
            });
            var childFuture = executor.submit(() -> {
                start.await();
                try {
                    return authService.refresh(child.getRefreshToken());
                } catch (AppException rejectedByReplay) {
                    assertThat(rejectedByReplay.getErrorCode()).isEqualTo(ErrorCode.INVALID_TOKEN);
                    return null;
                }
            });

            start.countDown();
            replayFuture.get(10, TimeUnit.SECONDS);
            TokenResponse possibleGrandchild = childFuture.get(10, TimeUnit.SECONDS);

            assertThat(refreshTokenRepository.findAllByFamilyId(familyId))
                    .isNotEmpty()
                    .allMatch(token -> token.getRevokedAt() != null);
            if (possibleGrandchild != null) {
                assertThatThrownBy(() -> authService.refresh(possibleGrandchild.getRefreshToken()))
                        .isInstanceOf(AppException.class)
                        .extracting(error -> ((AppException) error).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_TOKEN);
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
