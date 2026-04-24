package com.viralground.backend.service;

import com.viralground.backend.entity.*;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.payment.PaymentGateway;
import com.viralground.backend.repository.CampaignRepository;
import com.viralground.backend.repository.CompanyProfileRepository;
import com.viralground.backend.repository.EscrowTransactionRepository;
import com.viralground.backend.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EscrowServiceReleaseTest {

    @Mock CampaignRepository campaignRepository;
    @Mock EscrowTransactionRepository escrowTransactionRepository;
    @Mock CompanyProfileRepository companyProfileRepository;
    @Mock MemberRepository memberRepository;
    @Mock PaymentGateway paymentGateway;
    @Mock EmailService emailService;

    @InjectMocks
    EscrowService escrowService;

    Campaign campaign;

    @BeforeEach
    void setUp() {
        campaign = Campaign.builder()
                .id(1)
                .rewardAmount(50_000)
                .totalBudget(100_000)
                .escrowStatus(EscrowStatus.FUNDED)
                .build();
    }

    @Test
    void should_락건_조회_사용_when_release_호출() {
        // given — findByIdForUpdate 로만 조회해야 함 (동시 지급 중복 방지)
        when(campaignRepository.findByIdForUpdate(1)).thenReturn(Optional.of(campaign));
        when(escrowTransactionRepository.findByCampaignIdOrderByCreatedAtDesc(1)).thenReturn(List.of());
        when(paymentGateway.release(anyInt(), anyInt(), anyInt(), anyString()))
                .thenReturn(new PaymentGateway.ReleaseResult(true, "tx-1"));

        // when
        escrowService.release(1, 10, 50_000);

        // then
        verify(campaignRepository).findByIdForUpdate(1);
        ArgumentCaptor<EscrowTransaction> captor = ArgumentCaptor.forClass(EscrowTransaction.class);
        verify(escrowTransactionRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(EscrowTxType.RELEASE);
        assertThat(captor.getValue().getAmount()).isEqualTo(50_000);
    }

    @Test
    void should_INSUFFICIENT_ESCROW_BALANCE_예외_when_잔액_부족() {
        // given — 이미 80,000 지급됐는데 추가 50,000 요청하면 잔액 20,000 < 50,000
        when(campaignRepository.findByIdForUpdate(1)).thenReturn(Optional.of(campaign));
        when(escrowTransactionRepository.findByCampaignIdOrderByCreatedAtDesc(1))
                .thenReturn(List.of(
                        EscrowTransaction.builder()
                                .campaignId(1).type(EscrowTxType.RELEASE).amount(80_000).build()
                ));

        // when & then
        assertThatThrownBy(() -> escrowService.release(1, 10, 50_000))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INSUFFICIENT_ESCROW_BALANCE);
        verify(escrowTransactionRepository, org.mockito.Mockito.never()).save(any());
    }
}
