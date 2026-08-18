package com.viralground.backend.service;

import com.viralground.backend.entity.ApplicationStatus;
import com.viralground.backend.entity.ConnectionStatus;
import com.viralground.backend.entity.CreatorInstagramConnection;
import com.viralground.backend.entity.CreatorProfile;
import com.viralground.backend.entity.CampaignApplication;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountWithdrawalServiceTest {

    @Mock MemberRepository memberRepository;
    @Mock CampaignApplicationRepository applicationRepository;
    @Mock CampaignRepository campaignRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock CreatorInstagramConnectionRepository instagramConnectionRepository;
    @Mock CreatorProfileRepository creatorProfileRepository;
    @Mock InstagramConnectionProvider instagramConnectionProvider;
    @Mock MarketingConsentEventRepository marketingConsentEventRepository;

    private final Instant now = Instant.parse("2026-08-13T05:00:00Z");
    private AccountWithdrawalService service;

    @BeforeEach
    void setUp() {
        LegalDocumentProperties legalDocuments = new LegalDocumentProperties();
        legalDocuments.getDocuments().setMarketingVersion("marketing-2026-08-13");
        service = new AccountWithdrawalService(memberRepository, applicationRepository, campaignRepository,
                refreshTokenRepository, instagramConnectionRepository, creatorProfileRepository,
                instagramConnectionProvider, marketingConsentEventRepository, legalDocuments,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void withdrawsAccessWithoutDeletingLegalOrTransactionHistory() {
        Member member = Member.builder().id(7).email("creator@example.test").password("hash")
                .name("Creator").role(Role.CREATOR).status(MemberStatus.APPROVED)
                .emailVerified(true).marketingOptInAt(java.time.LocalDateTime.now()).build();
        CreatorInstagramConnection connection = CreatorInstagramConnection.builder()
                .id(3).creatorId(7).status(ConnectionStatus.CONNECTED)
                .providerAccountId("provider-id").providerUserId("provider-user")
                .igUsername("creator").encryptedAccessToken("ciphertext").build();
        CreatorProfile profile = CreatorProfile.builder().memberId(7)
                .publicProfileOptIn(true).publicProfileConsentedAt(java.time.LocalDateTime.now()).build();
        when(memberRepository.findById(7)).thenReturn(Optional.of(member));
        when(applicationRepository.existsByCreatorIdAndStatusIn(
                org.mockito.ArgumentMatchers.eq(7), anyCollection())).thenReturn(false);
        CampaignApplication pending = CampaignApplication.builder()
                .id(11).creatorId(7).campaignId(5).status(ApplicationStatus.PENDING).build();
        when(applicationRepository.findByCreatorIdAndStatus(7, ApplicationStatus.PENDING))
                .thenReturn(List.of(pending));
        when(instagramConnectionRepository.findByCreatorId(7)).thenReturn(Optional.of(connection));
        when(creatorProfileRepository.findByMemberId(7)).thenReturn(Optional.of(profile));

        service.withdrawCreator(7);

        ArgumentCaptor<Member> saved = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
        assertThat(saved.getValue().getWithdrawnAt()).isEqualTo(now);
        assertThat(saved.getValue().getMarketingOptInAt()).isNull();
        ArgumentCaptor<MarketingConsentEvent> marketingEvent =
                ArgumentCaptor.forClass(MarketingConsentEvent.class);
        verify(marketingConsentEventRepository).saveAndFlush(marketingEvent.capture());
        assertThat(marketingEvent.getValue().getAction()).isEqualTo(MarketingConsentAction.OPT_OUT);
        assertThat(marketingEvent.getValue().getDocumentVersion())
                .isEqualTo("marketing-2026-08-13");
        verify(refreshTokenRepository).deleteAllByMemberId(7);
        assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(connection.getEncryptedAccessToken()).isNull();
        assertThat(connection.getProviderAccountId()).isNull();
        assertThat(pending.getStatus()).isEqualTo(ApplicationStatus.WITHDRAWN);
        assertThat(profile.getPublicProfileOptIn()).isFalse();
        assertThat(profile.getPublicProfileConsentedAt()).isNull();
        verify(instagramConnectionProvider).revoke(connection);
        verify(memberRepository, never()).deleteById(7);
    }

    @Test
    void blocksWithdrawalWhileCreatorHasActiveContractWork() {
        Member member = Member.builder().id(7).email("creator@example.test").password("hash")
                .name("Creator").role(Role.CREATOR).status(MemberStatus.APPROVED)
                .emailVerified(true).build();
        when(memberRepository.findById(7)).thenReturn(Optional.of(member));
        when(applicationRepository.existsByCreatorIdAndStatusIn(
                org.mockito.ArgumentMatchers.eq(7), anyCollection())).thenReturn(true);

        assertThatThrownBy(() -> service.withdrawCreator(7))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ACCOUNT_HAS_ACTIVE_CAMPAIGN);
        verify(refreshTokenRepository, never()).deleteAllByMemberId(7);
        verify(memberRepository, never()).save(member);
    }

    @Test
    void withdrawsCompanyOnlyAfterCampaignAndEscrowWorkIsTerminal() {
        Member member = Member.builder().id(9).email("company@example.test").password("hash")
                .name("Company").role(Role.COMPANY).status(MemberStatus.APPROVED)
                .emailVerified(true).build();
        when(memberRepository.findById(9)).thenReturn(Optional.of(member));
        when(campaignRepository.existsActiveForCompany(
                org.mockito.ArgumentMatchers.eq(9), anyCollection(), anyCollection())).thenReturn(false);

        service.withdrawCompany(9);

        assertThat(member.getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
        assertThat(member.getWithdrawnAt()).isEqualTo(now);
        verify(refreshTokenRepository).deleteAllByMemberId(9);
        verify(memberRepository).save(member);
        verify(instagramConnectionProvider, never()).revoke(
                org.mockito.ArgumentMatchers.any(CreatorInstagramConnection.class));
    }

    @Test
    void blocksCompanyWithdrawalWhileCampaignOrEscrowIsActive() {
        Member member = Member.builder().id(9).email("company@example.test").password("hash")
                .name("Company").role(Role.COMPANY).status(MemberStatus.APPROVED)
                .emailVerified(true).build();
        when(memberRepository.findById(9)).thenReturn(Optional.of(member));
        when(campaignRepository.existsActiveForCompany(
                org.mockito.ArgumentMatchers.eq(9), anyCollection(), anyCollection())).thenReturn(true);

        assertThatThrownBy(() -> service.withdrawCompany(9))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ACCOUNT_HAS_ACTIVE_CAMPAIGN);
        verify(refreshTokenRepository, never()).deleteAllByMemberId(9);
        verify(memberRepository, never()).save(member);
    }
}
