package com.viralground.backend.service;

import com.viralground.backend.entity.ApplicationStatus;
import com.viralground.backend.entity.CampaignStatus;
import com.viralground.backend.entity.ConnectionStatus;
import com.viralground.backend.entity.CreatorInstagramConnection;
import com.viralground.backend.entity.EscrowStatus;
import com.viralground.backend.entity.Member;
import com.viralground.backend.entity.MemberStatus;
import com.viralground.backend.entity.MarketingConsentAction;
import com.viralground.backend.entity.MarketingConsentEvent;
import com.viralground.backend.entity.Role;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.CampaignApplicationRepository;
import com.viralground.backend.repository.CampaignRepository;
import com.viralground.backend.repository.CreatorInstagramConnectionRepository;
import com.viralground.backend.repository.CreatorProfileRepository;
import com.viralground.backend.repository.MemberRepository;
import com.viralground.backend.repository.MarketingConsentEventRepository;
import com.viralground.backend.repository.RefreshTokenRepository;
import com.viralground.backend.instagram.InstagramConnectionProvider;
import com.viralground.backend.legal.LegalDocumentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;

/**
 * 회원 탈퇴는 거래·법적 증적을 깨뜨리는 hard delete가 아니라 접근과 공개 노출을 끝내는
 * lifecycle transition이다. 보유기간 종료 후의 가명화/파기는 승인된 별도 운영 절차로 수행한다.
 */
@Service
@RequiredArgsConstructor
public class AccountWithdrawalService {

    private static final Set<ApplicationStatus> ACTIVE_CREATOR_WORK = Set.of(
            ApplicationStatus.APPROVED,
            ApplicationStatus.SUBMITTED,
            ApplicationStatus.CHANGES_REQUESTED);
    private static final Set<CampaignStatus> ACTIVE_COMPANY_CAMPAIGNS = Set.of(
            CampaignStatus.DRAFT,
            CampaignStatus.OPEN);
    private static final Set<EscrowStatus> ACTIVE_COMPANY_ESCROW = Set.of(
            EscrowStatus.PENDING_DEPOSIT,
            EscrowStatus.DEPOSIT_CONFIRMING,
            EscrowStatus.FUNDED,
            EscrowStatus.PARTIALLY_RELEASED);

    private final MemberRepository memberRepository;
    private final CampaignApplicationRepository applicationRepository;
    private final CampaignRepository campaignRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final CreatorInstagramConnectionRepository instagramConnectionRepository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final InstagramConnectionProvider instagramConnectionProvider;
    private final MarketingConsentEventRepository marketingConsentEventRepository;
    private final LegalDocumentProperties legalDocuments;
    private final Clock clock;

    @Transactional
    public void withdrawCreator(Integer memberId) {
        Member member = loadMember(memberId, Role.CREATOR);
        if (member.getStatus() == MemberStatus.WITHDRAWN) {
            return;
        }
        if (applicationRepository.existsByCreatorIdAndStatusIn(memberId, ACTIVE_CREATOR_WORK)) {
            throw new AppException(ErrorCode.ACCOUNT_HAS_ACTIVE_CAMPAIGN);
        }

        applicationRepository.findByCreatorIdAndStatus(memberId, ApplicationStatus.PENDING)
                .forEach(application -> application.setStatus(ApplicationStatus.WITHDRAWN));
        creatorProfileRepository.findByMemberId(memberId).ifPresent(profile -> {
            profile.setPublicProfileOptIn(false);
            profile.setPublicProfileConsentedAt(null);
        });
        disconnectProviderAndLocally(memberId);
        completeWithdrawal(member);
    }

    @Transactional
    public void withdrawCompany(Integer memberId) {
        Member member = loadMember(memberId, Role.COMPANY);
        if (member.getStatus() == MemberStatus.WITHDRAWN) {
            return;
        }
        if (campaignRepository.existsActiveForCompany(
                memberId, ACTIVE_COMPANY_CAMPAIGNS, ACTIVE_COMPANY_ESCROW)) {
            throw new AppException(ErrorCode.ACCOUNT_HAS_ACTIVE_CAMPAIGN);
        }
        completeWithdrawal(member);
    }

    private Member loadMember(Integer memberId, Role expectedRole) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        if (member.getRole() != expectedRole) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        return member;
    }

    private void completeWithdrawal(Member member) {
        refreshTokenRepository.deleteAllByMemberId(member.getId());
        Instant withdrawnAt = clock.instant();
        if (member.getMarketingOptInAt() != null) {
            marketingConsentEventRepository.saveAndFlush(new MarketingConsentEvent(
                    member.getId(), MarketingConsentAction.OPT_OUT,
                    legalDocuments.getDocuments().getMarketingVersion(), withdrawnAt));
            member.setMarketingOptInAt(null);
        }
        member.withdraw(withdrawnAt);
        memberRepository.save(member);
    }

    private void disconnectProviderAndLocally(Integer memberId) {
        instagramConnectionRepository.findByCreatorId(memberId).ifPresent(connection -> {
            // 외부 토큰 철회 실패 시 탈퇴를 완료한 척하지 않는다. 사용자가 재시도하거나 지원팀이
            // 공급자 상태를 확인해야 로컬 증적과 Meta 권한이 어긋나지 않는다.
            instagramConnectionProvider.revoke(connection);
            clearTokenMaterial(connection);
            instagramConnectionRepository.save(connection);
        });
    }

    private static void clearTokenMaterial(CreatorInstagramConnection connection) {
        connection.setStatus(ConnectionStatus.DISCONNECTED);
        connection.setProviderAccountId(null);
        connection.setProviderUserId(null);
        connection.setIgUsername(null);
        connection.setEncryptedAccessToken(null);
        connection.setAccessTokenExpiresAt(null);
        connection.setTokenRefreshedAt(null);
        connection.setConnectedAt(null);
        connection.setLastError(null);
    }
}
