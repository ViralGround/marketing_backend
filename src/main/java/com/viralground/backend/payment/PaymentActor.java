package com.viralground.backend.payment;

/** 결제 감사 로그에 기록하는 행위자. 이메일·이름 등 개인정보는 넣지 않는다. */
public record PaymentActor(Integer memberId, String type) {

    public PaymentActor {
        type = type == null || type.isBlank() ? "SYSTEM" : type.trim().toUpperCase();
    }

    public static PaymentActor admin(Integer memberId) {
        return new PaymentActor(memberId, "ADMIN");
    }

    public static PaymentActor company(Integer memberId) {
        return new PaymentActor(memberId, "COMPANY");
    }

    public static PaymentActor system() {
        return new PaymentActor(null, "SYSTEM");
    }
}
