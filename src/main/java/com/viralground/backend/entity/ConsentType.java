package com.viralground.backend.entity;

/** 가입 시 동의한 법적 문서의 종류. DB와 운영 문서에서 사용하는 안정적인 식별자다. */
public enum ConsentType {
    TERMS_OF_SERVICE,
    PRIVACY_POLICY,
    AGE_14_CONFIRMATION,
    CREATOR_THIRD_PARTY_PROVISION,
    MARKETING_COMMUNICATION
}
