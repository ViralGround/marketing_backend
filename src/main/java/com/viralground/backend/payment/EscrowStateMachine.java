package com.viralground.backend.payment;

import com.viralground.backend.entity.EscrowStatus;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/** 에스크로 상태 변경 규칙의 단일 출처. 서비스 곳곳에서 임의 상태 변경을 막는다. */
public final class EscrowStateMachine {

    private static final Map<EscrowStatus, EnumSet<EscrowStatus>> ALLOWED =
            new EnumMap<>(EscrowStatus.class);

    static {
        allow(EscrowStatus.PENDING_DEPOSIT, EscrowStatus.DEPOSIT_CONFIRMING, EscrowStatus.FUNDED, EscrowStatus.REFUNDED);
        allow(EscrowStatus.DEPOSIT_CONFIRMING, EscrowStatus.PENDING_DEPOSIT, EscrowStatus.FUNDED, EscrowStatus.REFUNDED);
        allow(EscrowStatus.NONE, EscrowStatus.FUNDED, EscrowStatus.REFUNDED);
        allow(EscrowStatus.FUNDED, EscrowStatus.PARTIALLY_RELEASED, EscrowStatus.RELEASED, EscrowStatus.REFUNDED);
        allow(EscrowStatus.PARTIALLY_RELEASED, EscrowStatus.PARTIALLY_RELEASED, EscrowStatus.RELEASED, EscrowStatus.REFUNDED);
    }

    private EscrowStateMachine() {}

    private static void allow(EscrowStatus from, EscrowStatus... targets) {
        ALLOWED.put(from, EnumSet.copyOf(java.util.List.of(targets)));
    }

    public static void require(EscrowStatus from, EscrowStatus to) {
        if (from == null || to == null || !ALLOWED.getOrDefault(from, EnumSet.noneOf(EscrowStatus.class)).contains(to)) {
            throw new AppException(ErrorCode.INVALID_ESCROW_STATE);
        }
    }
}
