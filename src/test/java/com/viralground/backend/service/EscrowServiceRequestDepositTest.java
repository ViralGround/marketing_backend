package com.viralground.backend.service;

import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.EscrowStatus;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.payment.PaymentGateway;
import com.viralground.backend.repository.CampaignRepository;
import com.viralground.backend.repository.CompanyProfileRepository;
import com.viralground.backend.repository.EscrowTransactionRepository;
import com.viralground.backend.repository.MemberRepository;
import com.viralground.backend.repository.PaymentLedgerEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EscrowServiceRequestDepositTest {

    @Mock CampaignRepository campaignRepository;
    @Mock EscrowTransactionRepository transactionRepository;
    @Mock PaymentLedgerEntryRepository ledgerRepository;
    @Mock CompanyProfileRepository companyProfileRepository;
    @Mock MemberRepository memberRepository;
    @Mock PaymentGateway paymentGateway;
    @Mock EmailService emailService;

    @InjectMocks
    EscrowService service;

    Campaign campaign;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "paymentsFeatureEnabled", true);
        campaign = Campaign.builder()
                .id(17)
                .createdById(51)
                .title("disabled gateway campaign")
                .totalBudget(100_000)
                .escrowStatus(EscrowStatus.PENDING_DEPOSIT)
                .build();
    }

    @Test
    void disabledGatewayFailsClosedWithoutLeavingCampaignDepositConfirming() {
        when(campaignRepository.findByIdForUpdate(17)).thenReturn(Optional.of(campaign));
        when(paymentGateway.providerName()).thenReturn("disabled");

        assertThatThrownBy(() -> service.requestDeposit(17, 51))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE);

        verify(campaignRepository).findByIdForUpdate(17);
        verify(campaignRepository, never()).save(campaign);
        assertThat(campaign.getEscrowStatus()).isEqualTo(EscrowStatus.PENDING_DEPOSIT);
        assertThat(campaign.getDepositRequestedAt()).isNull();
        verifyNoInteractions(companyProfileRepository, emailService,
                transactionRepository, ledgerRepository, memberRepository);
    }

    @Test
    void ownerCheckStillPrecedesDisabledGatewayDisclosure() {
        when(campaignRepository.findByIdForUpdate(17)).thenReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.requestDeposit(17, 999))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FORBIDDEN);

        verifyNoInteractions(paymentGateway, companyProfileRepository, emailService);
        verify(campaignRepository, never()).save(campaign);
        assertThat(campaign.getEscrowStatus()).isEqualTo(EscrowStatus.PENDING_DEPOSIT);
        assertThat(campaign.getDepositRequestedAt()).isNull();
    }
}
