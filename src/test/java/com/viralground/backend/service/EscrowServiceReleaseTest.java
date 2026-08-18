package com.viralground.backend.service;

import com.viralground.backend.entity.*;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.payment.PaymentActor;
import com.viralground.backend.payment.PaymentGateway;
import com.viralground.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EscrowServiceReleaseTest {

    @Mock CampaignRepository campaignRepository;
    @Mock EscrowTransactionRepository transactionRepository;
    @Mock PaymentLedgerEntryRepository ledgerRepository;
    @Mock CompanyProfileRepository companyProfileRepository;
    @Mock MemberRepository memberRepository;
    @Mock PaymentGateway paymentGateway;
    @Mock EmailService emailService;

    @InjectMocks EscrowService service;
    Campaign campaign;

    @BeforeEach
    void setUp() {
        campaign = Campaign.builder().id(1).totalBudget(100_000)
                .escrowStatus(EscrowStatus.FUNDED).build();
    }

    @Test
    void releasesOnceAndWritesBalancedLedger() {
        arrangeNewRelease(100_000, 0, new PaymentGateway.ReleaseResult(true, "rel-1"));

        EscrowTransaction tx = service.release(
                1, 10, 50_000, PaymentActor.admin(7), "approved", "release-1");

        assertThat(tx.getType()).isEqualTo(EscrowTxType.RELEASE);
        assertThat(tx.getBalanceAfter()).isEqualTo(50_000);
        assertThat(campaign.getEscrowStatus()).isEqualTo(EscrowStatus.PARTIALLY_RELEASED);
        verify(campaignRepository).findByIdForUpdate(1);
        verify(ledgerRepository).saveAll(argThat(entries -> {
            List<PaymentLedgerEntry> saved = StreamSupport
                    .stream(entries.spliterator(), false)
                    .toList();
            return saved.size() == 2
                    && saved.get(0).getAmount().equals(saved.get(1).getAmount())
                    && saved.get(0).getDirection() != saved.get(1).getDirection();
        }));
    }

    @Test
    void fullReleaseMovesToReleasedTerminalState() {
        arrangeNewRelease(100_000, 50_000, new PaymentGateway.ReleaseResult(true, "rel-2"));

        service.release(1, 10, 50_000, PaymentActor.admin(7), "approved", "release-1");

        assertThat(campaign.getEscrowStatus()).isEqualTo(EscrowStatus.RELEASED);
    }

    @Test
    void insufficientBalanceNeverCallsGateway() {
        arrangeBalanceOnly(100_000, 80_000, 0);

        assertThatThrownBy(() -> service.release(
                1, 10, 50_000, PaymentActor.admin(7), "approved", "release-1"))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INSUFFICIENT_ESCROW_BALANCE);
        verify(paymentGateway, never()).release(anyInt(), anyInt(), anyInt(), anyString(), anyString(), anyString());
        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    void gatewayFailureNeverSettlesOrWritesLedger() {
        arrangeNewRelease(100_000, 0, new PaymentGateway.ReleaseResult(false, null));

        assertThatThrownBy(() -> service.release(
                1, 10, 50_000, PaymentActor.admin(7), "approved", "release-1"))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PAYMENT_GATEWAY_REJECTED);

        assertThat(campaign.getEscrowStatus()).isEqualTo(EscrowStatus.FUNDED);
        verify(transactionRepository, never()).saveAndFlush(any());
        verifyNoInteractions(ledgerRepository);
    }

    @Test
    void replayIsSafeEvenAfterFullRelease() {
        campaign.setEscrowStatus(EscrowStatus.RELEASED);
        EscrowTransaction existing = releaseTransaction(50_000, "release-1");
        when(campaignRepository.findByIdForUpdate(1)).thenReturn(Optional.of(campaign));
        when(transactionRepository.findByIdempotencyKey("release-1"))
                .thenReturn(Optional.of(existing));

        assertThat(service.release(
                1, 10, 50_000, PaymentActor.admin(7), "approved", "release-1"))
                .isSameAs(existing);
        verifyNoInteractions(paymentGateway, ledgerRepository);
    }

    @Test
    void applicationCannotBePaidAgainWithAnotherKey() {
        when(campaignRepository.findByIdForUpdate(1)).thenReturn(Optional.of(campaign));
        when(transactionRepository.findByIdempotencyKey("another-key")).thenReturn(Optional.empty());
        when(transactionRepository.findFirstByCampaignIdAndApplicationIdAndType(
                1, 10, EscrowTxType.RELEASE)).thenReturn(Optional.of(releaseTransaction(50_000, "first")));

        assertThatThrownBy(() -> service.release(
                1, 10, 50_000, PaymentActor.admin(7), "approved", "another-key"))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PAYMENT_IDEMPOTENCY_CONFLICT);
        verifyNoInteractions(paymentGateway, ledgerRepository);
    }

    @Test
    void zeroOrNegativeAmountIsRejectedBeforeDatabaseAccess() {
        assertThatThrownBy(() -> service.release(1, 10, 0))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PAYMENT_AMOUNT);
        verifyNoInteractions(campaignRepository, paymentGateway, ledgerRepository);
    }

    private void arrangeNewRelease(long deposits, long releases, PaymentGateway.ReleaseResult result) {
        arrangeBalanceOnly(deposits, releases, 0);
        when(paymentGateway.providerName()).thenReturn("test-provider");
        when(paymentGateway.release(1, 10, 50_000, "KRW", "release-1", "approved"))
                .thenReturn(result);
        if (result.success()) {
            when(transactionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        }
    }

    private void arrangeBalanceOnly(long deposits, long releases, long refunds) {
        when(campaignRepository.findByIdForUpdate(1)).thenReturn(Optional.of(campaign));
        when(transactionRepository.findByIdempotencyKey("release-1")).thenReturn(Optional.empty());
        when(transactionRepository.findFirstByCampaignIdAndApplicationIdAndType(
                1, 10, EscrowTxType.RELEASE)).thenReturn(Optional.empty());
        when(transactionRepository.sumAmountByCampaignIdAndType(1, EscrowTxType.DEPOSIT)).thenReturn(deposits);
        when(transactionRepository.sumAmountByCampaignIdAndType(1, EscrowTxType.RELEASE)).thenReturn(releases);
        when(transactionRepository.sumAmountByCampaignIdAndType(1, EscrowTxType.REFUND)).thenReturn(refunds);
    }

    private static EscrowTransaction releaseTransaction(int amount, String key) {
        return EscrowTransaction.builder()
                .campaignId(1).applicationId(10).type(EscrowTxType.RELEASE).amount(amount)
                .currency("KRW").operationId("op").idempotencyKey(key)
                .provider("provider").providerTxId("provider-tx")
                .actorType("ADMIN").reason("approved").memo("approved")
                .balanceAfter(0).build();
    }
}
