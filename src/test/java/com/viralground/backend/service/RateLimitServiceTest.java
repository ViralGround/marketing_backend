package com.viralground.backend.service;

import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class RateLimitServiceTest {

    @Test
    void localBackendAppliesLoginLimitWithoutStoringRawIdentity() {
        RateLimitService service = new RateLimitService(
                mock(StringRedisTemplate.class), "local", "test:rate", true);

        for (int i = 0; i < 10; i++) service.consumeLoginByEmail("Person@example.test");

        assertThatThrownBy(() -> service.consumeLoginByEmail("person@example.test"))
                .isInstanceOf(AppException.class)
                .extracting(error -> ((AppException) error).getErrorCode())
                .isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisFailureClosesAuthenticationBoundaryButNotPublicContact() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doThrow(new DataAccessResourceFailureException("redis unavailable"))
                .when(redis).execute(any(RedisScript.class), anyList(), any(), any());
        RateLimitService service = new RateLimitService(redis, "redis", "test:rate", true);

        assertThatThrownBy(() -> service.consumeLoginByIp("127.0.0.1"))
                .isInstanceOf(AppException.class)
                .extracting(error -> ((AppException) error).getErrorCode())
                .isEqualTo(ErrorCode.RATE_LIMIT_BACKEND_UNAVAILABLE);
        assertThatCode(() -> service.consumeContactByIp("127.0.0.1"))
                .doesNotThrowAnyException();
    }

    @Test
    void verificationResetAndRefreshHaveIndependentIdentityLimits() {
        RateLimitService service = new RateLimitService(
                mock(StringRedisTemplate.class), "local", "test:rate", true);

        for (int i = 0; i < 10; i++) service.consumeVerifyCodeByEmail("member@example.test");
        assertThatThrownBy(() -> service.consumeVerifyCodeByEmail("MEMBER@example.test"))
                .isInstanceOf(AppException.class)
                .extracting(error -> ((AppException) error).getErrorCode())
                .isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED);

        for (int i = 0; i < 5; i++) service.consumePasswordResetConfirmByEmail("reset@example.test");
        assertThatThrownBy(() -> service.consumePasswordResetConfirmByEmail("reset@example.test"))
                .isInstanceOf(AppException.class);

        for (int i = 0; i < 10; i++) service.consumeRefreshByToken("opaque-device-refresh-token");
        assertThatThrownBy(() -> service.consumeRefreshByToken("opaque-device-refresh-token"))
                .isInstanceOf(AppException.class);
        assertThatCode(() -> service.consumeRefreshByToken("another-device-refresh-token"))
                .doesNotThrowAnyException();
    }
}
