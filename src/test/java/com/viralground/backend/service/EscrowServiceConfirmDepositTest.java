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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EscrowServiceConfirmDepositTest {

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
                .totalBudget(100_000)
                .status(CampaignStatus.DRAFT)
                .escrowStatus(EscrowStatus.DEPOSIT_CONFIRMING)
                .createdById(99)
                .build();
    }

    @Test
    void should_락건_조회_사용_when_confirmDeposit_호출() {
        // given
        when(campaignRepository.findByIdForUpdate(1)).thenReturn(Optional.of(campaign));
        when(escrowTransactionRepository.existsByCampaignIdAndType(1, EscrowTxType.DEPOSIT)).thenReturn(false);
        when(paymentGateway.confirmDeposit(anyInt(), anyInt(), anyString()))
                .thenReturn(new PaymentGateway.DepositResult(true, "dep-1"));

        // when
        escrowService.confirmDeposit(1);

        // then — 락건 조회 + DEPOSIT 트랜잭션 저장
        verify(campaignRepository).findByIdForUpdate(1);
        verify(escrowTransactionRepository).save(any(EscrowTransaction.class));
    }

    @Test
    void should_INVALID_ESCROW_STATE_예외_when_이미_DEPOSIT_존재() {
        // given — 멱등성: 이미 DEPOSIT 트랜잭션이 있으면 중복 실행 거부
        when(campaignRepository.findByIdForUpdate(1)).thenReturn(Optional.of(campaign));
        when(escrowTransactionRepository.existsByCampaignIdAndType(1, EscrowTxType.DEPOSIT)).thenReturn(true);

        // when & then
        assertThatThrownBy(() -> escrowService.confirmDeposit(1))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_ESCROW_STATE);
        verify(paymentGateway, never()).confirmDeposit(anyInt(), anyInt(), anyString());
        verify(escrowTransactionRepository, never()).save(any());
    }
}
