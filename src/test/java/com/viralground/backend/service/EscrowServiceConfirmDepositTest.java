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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EscrowServiceConfirmDepositTest {

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
        campaign = Campaign.builder()
                .id(1).title("campaign").totalBudget(100_000)
                .status(CampaignStatus.DRAFT)
                .escrowStatus(EscrowStatus.DEPOSIT_CONFIRMING)
                .createdById(99).build();
    }

    @Test
    void confirmsOnlyAfterGatewaySuccessAndWritesBalancedLedger() {
        arrangeNewDeposit(new PaymentGateway.DepositResult(true, "dep-1"));

        EscrowTransaction result = service.confirmDeposit(
                1, PaymentActor.admin(7), "bank receipt checked", "dep-request-1");

        assertThat(result.getProviderTxId()).isEqualTo("dep-1");
        assertThat(result.getActorMemberId()).isEqualTo(7);
        assertThat(result.getIdempotencyKey()).isEqualTo("dep-request-1");
        assertThat(result.getBalanceAfter()).isEqualTo(100_000);
        assertThat(campaign.getEscrowStatus()).isEqualTo(EscrowStatus.FUNDED);
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.OPEN);
        verify(campaignRepository).findByIdForUpdate(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PaymentLedgerEntry>> entries = ArgumentCaptor.forClass(List.class);
        verify(ledgerRepository).saveAll(entries.capture());
        assertThat(entries.getValue())
                .extracting(PaymentLedgerEntry::getDirection)
                .containsExactly(PaymentLedgerDirection.DEBIT, PaymentLedgerDirection.CREDIT);
        assertThat(entries.getValue()).allSatisfy(e -> assertThat(e.getAmount()).isEqualTo(100_000));
    }

    @Test
    void leavesStateAndLedgerUntouchedWhenGatewayRejects() {
        arrangeNewDeposit(new PaymentGateway.DepositResult(false, null));

        assertThatThrownBy(() -> service.confirmDeposit(
                1, PaymentActor.admin(7), "receipt mismatch", "dep-request-1"))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PAYMENT_GATEWAY_REJECTED);

        assertThat(campaign.getEscrowStatus()).isEqualTo(EscrowStatus.DEPOSIT_CONFIRMING);
        verify(transactionRepository, never()).saveAndFlush(any());
        verify(ledgerRepository, never()).saveAll(any());
        verify(campaignRepository, never()).save(campaign);
    }

    @Test
    void disabledGatewayFailsClosed() {
        when(campaignRepository.findByIdForUpdate(1)).thenReturn(Optional.of(campaign));
        when(transactionRepository.findByIdempotencyKey("dep-request-1")).thenReturn(Optional.empty());
        when(transactionRepository.existsByCampaignIdAndType(1, EscrowTxType.DEPOSIT)).thenReturn(false);
        when(paymentGateway.providerName()).thenReturn("disabled");
        when(paymentGateway.confirmDeposit(1, 100_000, "KRW", "dep-request-1", "confirm"))
                .thenReturn(new PaymentGateway.DepositResult(false, null));

        assertThatThrownBy(() -> service.confirmDeposit(
                1, PaymentActor.admin(7), "confirm", "dep-request-1"))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE);
    }

    @Test
    void sameIdempotencyKeyIsSafeAfterCampaignAlreadyFunded() {
        campaign.setEscrowStatus(EscrowStatus.FUNDED);
        EscrowTransaction existing = transaction(
                EscrowTxType.DEPOSIT, null, 100_000, "dep-request-1");
        when(campaignRepository.findByIdForUpdate(1)).thenReturn(Optional.of(campaign));
        when(transactionRepository.findByIdempotencyKey("dep-request-1"))
                .thenReturn(Optional.of(existing));

        EscrowTransaction replay = service.confirmDeposit(
                1, PaymentActor.admin(7), "confirm", "dep-request-1");

        assertThat(replay).isSameAs(existing);
        verifyNoInteractions(paymentGateway, ledgerRepository);
    }

    @Test
    void sameIdempotencyKeyCannotBeReusedForDifferentAmount() {
        EscrowTransaction existing = transaction(
                EscrowTxType.DEPOSIT, null, 90_000, "dep-request-1");
        when(campaignRepository.findByIdForUpdate(1)).thenReturn(Optional.of(campaign));
        when(transactionRepository.findByIdempotencyKey("dep-request-1"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.confirmDeposit(
                1, PaymentActor.admin(7), "confirm", "dep-request-1"))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PAYMENT_IDEMPOTENCY_CONFLICT);
        verifyNoInteractions(paymentGateway, ledgerRepository);
    }

    private void arrangeNewDeposit(PaymentGateway.DepositResult gatewayResult) {
        when(campaignRepository.findByIdForUpdate(1)).thenReturn(Optional.of(campaign));
        when(transactionRepository.findByIdempotencyKey("dep-request-1")).thenReturn(Optional.empty());
        when(transactionRepository.existsByCampaignIdAndType(1, EscrowTxType.DEPOSIT)).thenReturn(false);
        when(paymentGateway.providerName()).thenReturn("test-provider");
        when(paymentGateway.confirmDeposit(eq(1), eq(100_000), eq("KRW"),
                eq("dep-request-1"), anyString()))
                .thenReturn(gatewayResult);
        if (gatewayResult.success()) {
            when(transactionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        }
    }

    private static EscrowTransaction transaction(EscrowTxType type, Integer applicationId,
                                                 int amount, String key) {
        return EscrowTransaction.builder()
                .campaignId(1).applicationId(applicationId).type(type).amount(amount)
                .currency("KRW").operationId("op").idempotencyKey(key)
                .provider("provider").providerTxId("provider-tx")
                .actorType("ADMIN").reason("reason").memo("reason")
                .balanceAfter(amount).build();
    }
}
