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

import java.util.Optional;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EscrowServiceRefundTest {

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
                .escrowStatus(EscrowStatus.PARTIALLY_RELEASED).build();
    }

    @Test
    void refundsOnlyRemainingBalanceAndMovesToRefunded() {
        arrangeNewRefund(new PaymentGateway.RefundResult(true, "refund-1"));

        EscrowTransaction tx = service.refund(
                1, PaymentActor.company(99), "cancelled", "refund-req-1");

        assertThat(tx.getAmount()).isEqualTo(60_000);
        assertThat(tx.getBalanceAfter()).isZero();
        assertThat(campaign.getEscrowStatus()).isEqualTo(EscrowStatus.REFUNDED);
        assertThat(campaign.getRefundedAt()).isNotNull();
        verify(paymentGateway).refund(1, 60_000, "KRW", "refund-req-1", "cancelled");
        verify(ledgerRepository).saveAll(argThat(entries ->
                StreamSupport.stream(entries.spliterator(), false).count() == 2));
    }

    @Test
    void gatewayFailureLeavesStateUntouched() {
        arrangeNewRefund(new PaymentGateway.RefundResult(false, null));

        assertThatThrownBy(() -> service.refund(
                1, PaymentActor.company(99), "cancelled", "refund-req-1"))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PAYMENT_GATEWAY_REJECTED);
        assertThat(campaign.getEscrowStatus()).isEqualTo(EscrowStatus.PARTIALLY_RELEASED);
        verify(transactionRepository, never()).saveAndFlush(any());
        verifyNoInteractions(ledgerRepository);
    }

    @Test
    void replayReturnsOriginalRefundAfterTerminalState() {
        campaign.setEscrowStatus(EscrowStatus.REFUNDED);
        EscrowTransaction existing = refundTransaction();
        when(campaignRepository.findByIdForUpdate(1)).thenReturn(Optional.of(campaign));
        when(transactionRepository.findByIdempotencyKey("refund-req-1"))
                .thenReturn(Optional.of(existing));

        assertThat(service.refund(
                1, PaymentActor.company(99), "cancelled", "refund-req-1"))
                .isSameAs(existing);
        verifyNoInteractions(paymentGateway, ledgerRepository);
    }

    @Test
    void inconsistentNegativeLedgerBalanceFailsBeforeGateway() {
        when(campaignRepository.findByIdForUpdate(1)).thenReturn(Optional.of(campaign));
        when(transactionRepository.findByIdempotencyKey("refund-req-1")).thenReturn(Optional.empty());
        when(transactionRepository.sumAmountByCampaignIdAndType(1, EscrowTxType.DEPOSIT)).thenReturn(10_000L);
        when(transactionRepository.sumAmountByCampaignIdAndType(1, EscrowTxType.RELEASE)).thenReturn(20_000L);
        when(transactionRepository.sumAmountByCampaignIdAndType(1, EscrowTxType.REFUND)).thenReturn(0L);

        assertThatThrownBy(() -> service.refund(
                1, PaymentActor.company(99), "cancelled", "refund-req-1"))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_ESCROW_STATE);
        verifyNoInteractions(paymentGateway, ledgerRepository);
    }

    private void arrangeNewRefund(PaymentGateway.RefundResult result) {
        when(campaignRepository.findByIdForUpdate(1)).thenReturn(Optional.of(campaign));
        when(transactionRepository.findByIdempotencyKey("refund-req-1")).thenReturn(Optional.empty());
        when(transactionRepository.sumAmountByCampaignIdAndType(1, EscrowTxType.DEPOSIT)).thenReturn(100_000L);
        when(transactionRepository.sumAmountByCampaignIdAndType(1, EscrowTxType.RELEASE)).thenReturn(40_000L);
        when(transactionRepository.sumAmountByCampaignIdAndType(1, EscrowTxType.REFUND)).thenReturn(0L);
        when(paymentGateway.providerName()).thenReturn("test-provider");
        when(paymentGateway.refund(1, 60_000, "KRW", "refund-req-1", "cancelled"))
                .thenReturn(result);
        if (result.success()) {
            when(transactionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        }
    }

    private static EscrowTransaction refundTransaction() {
        return EscrowTransaction.builder()
                .campaignId(1).type(EscrowTxType.REFUND).amount(60_000)
                .currency("KRW").operationId("op").idempotencyKey("refund-req-1")
                .provider("provider").providerTxId("refund-1")
                .actorType("COMPANY").reason("cancelled").memo("cancelled")
                .balanceAfter(0).build();
    }
}
