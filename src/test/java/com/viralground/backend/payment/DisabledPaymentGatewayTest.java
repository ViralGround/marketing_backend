package com.viralground.backend.payment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DisabledPaymentGatewayTest {

    private final DisabledPaymentGateway gateway = new DisabledPaymentGateway();

    @Test
    void everyMoneyOperationFailsClosed() {
        assertThat(gateway.confirmDeposit(1, 100, "KRW", "a", "reason").success()).isFalse();
        assertThat(gateway.release(1, 2, 100, "KRW", "b", "reason").success()).isFalse();
        assertThat(gateway.refund(1, 100, "KRW", "c", "reason").success()).isFalse();
        assertThat(gateway.lookup("unknown").status()).isEqualTo(PaymentGateway.RemoteStatus.UNKNOWN);
    }
}
