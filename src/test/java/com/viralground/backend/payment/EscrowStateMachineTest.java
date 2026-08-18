package com.viralground.backend.payment;

import com.viralground.backend.entity.EscrowStatus;
import com.viralground.backend.exception.AppException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class EscrowStateMachineTest {

    @Test
    void allowsDocumentedTransitions() {
        assertThatCode(() -> EscrowStateMachine.require(
                EscrowStatus.PENDING_DEPOSIT, EscrowStatus.DEPOSIT_CONFIRMING)).doesNotThrowAnyException();
        assertThatCode(() -> EscrowStateMachine.require(
                EscrowStatus.FUNDED, EscrowStatus.PARTIALLY_RELEASED)).doesNotThrowAnyException();
        assertThatCode(() -> EscrowStateMachine.require(
                EscrowStatus.PARTIALLY_RELEASED, EscrowStatus.RELEASED)).doesNotThrowAnyException();
    }

    @Test
    void rejectsTerminalAndImpossibleTransitions() {
        assertThatThrownBy(() -> EscrowStateMachine.require(
                EscrowStatus.REFUNDED, EscrowStatus.FUNDED)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> EscrowStateMachine.require(
                EscrowStatus.RELEASED, EscrowStatus.REFUNDED)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> EscrowStateMachine.require(
                EscrowStatus.NONE, EscrowStatus.RELEASED)).isInstanceOf(AppException.class);
    }
}
