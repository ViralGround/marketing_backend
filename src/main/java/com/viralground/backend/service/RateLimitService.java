package com.viralground.backend.service;

import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 인증 경계 rate limit. local backend는 단일 JVM 개발/test 전용이고,
 * redis backend는 모든 replica가 동일한 원자적 카운터를 공유한다.
 *
 * Redis 장애 시 인증·가입·코드 API는 fail-closed한다. 공개 상담 API는
 * availability를 위해 fail-open하되 오류를 구조화 로그로 남긴다.
 */
@Service
@Slf4j
public class RateLimitService {

    private static final long ENTRY_TTL_SECONDS = 3600;
    private static final Set<String> SUPPORTED_BACKENDS = Set.of("local", "redis");
    private static final DefaultRedisScript<Long> CONSUME_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            if current > tonumber(ARGV[1]) then
              return 0
            end
            return 1
            """, Long.class);

    private final ConcurrentHashMap<String, Entry> buckets = new ConcurrentHashMap<>();
    private final AtomicLong operations = new AtomicLong();
    private final StringRedisTemplate redisTemplate;
    private final String backend;
    private final String redisKeyPrefix;
    private final boolean authFailClosed;

    public RateLimitService(
            StringRedisTemplate redisTemplate,
            @Value("${rate-limit.backend:local}") String backend,
            @Value("${rate-limit.redis-key-prefix:viralground:rate-limit}") String redisKeyPrefix,
            @Value("${rate-limit.auth-fail-closed:true}") boolean authFailClosed) {
        this.redisTemplate = redisTemplate;
        this.backend = backend == null ? "local" : backend.trim().toLowerCase(Locale.ROOT);
        this.redisKeyPrefix = redisKeyPrefix == null || redisKeyPrefix.isBlank()
                ? "viralground:rate-limit" : redisKeyPrefix.trim();
        this.authFailClosed = authFailClosed;
        if (!SUPPORTED_BACKENDS.contains(this.backend)) {
            throw new IllegalStateException("RATE_LIMIT_BACKEND는 local 또는 redis여야 합니다.");
        }
    }

    public void consumeLoginByIp(String ip) {
        consume("login:ip", ip, true, dual(10, Duration.ofMinutes(1), 30, Duration.ofMinutes(10)));
    }

    public void consumeLoginByEmail(String email) {
        consume("login:email", email, true, dual(10, Duration.ofMinutes(1), 30, Duration.ofMinutes(10)));
    }

    public void consumeSignupByIp(String ip) {
        consume("signup:ip", ip, true, one(5, Duration.ofMinutes(10)));
    }

    public void consumeSignupByEmail(String email) {
        consume("signup:email", email, true, one(3, Duration.ofMinutes(10)));
    }

    public void consumeRequestCodeByIp(String ip) {
        consume("reqcode:ip", ip, true, one(3, Duration.ofMinutes(1)));
    }

    public void consumeRequestCodeByEmail(String email) {
        consume("reqcode:email", email, true, one(3, Duration.ofMinutes(1)));
    }

    public void consumeVerifyCodeByIp(String ip) {
        consume("verifycode:ip", ip, true, one(20, Duration.ofMinutes(1)));
    }

    public void consumeVerifyCodeByEmail(String email) {
        consume("verifycode:email", email, true, one(10, Duration.ofMinutes(1)));
    }

    public void consumePasswordResetRequestByIp(String ip) {
        consume("pwreset:req:ip", ip, true, one(3, Duration.ofMinutes(1)));
    }

    public void consumePasswordResetRequestByEmail(String email) {
        consume("pwreset:req:email", email, true, one(3, Duration.ofMinutes(1)));
    }

    public void consumePasswordResetConfirmByIp(String ip) {
        consume("pwreset:confirm:ip", ip, true, one(10, Duration.ofMinutes(1)));
    }

    public void consumePasswordResetConfirmByEmail(String email) {
        consume("pwreset:confirm:email", email, true, one(5, Duration.ofMinutes(1)));
    }

    public void consumeRefreshByIp(String ip) {
        consume("refresh:ip", ip, true, dual(30, Duration.ofMinutes(1), 120, Duration.ofMinutes(10)));
    }

    /** The refresh cookie is a per-session/device identity; only its SHA-256 fingerprint reaches Redis. */
    public void consumeRefreshByToken(String refreshToken) {
        consume("refresh:token", refreshToken, true, one(10, Duration.ofMinutes(1)));
    }

    public void consumeContactByIp(String ip) {
        consume("contact:ip", ip, false, one(3, Duration.ofMinutes(10)));
    }

    private void consume(String namespace, String identity, boolean authenticationBoundary,
                         List<Limit> limits) {
        String identityHash = fingerprint(identity);
        if ("redis".equals(backend)) {
            consumeRedis(namespace, identityHash, authenticationBoundary, limits);
            return;
        }
        consumeLocal(namespace + ':' + identityHash, limits);
    }

    private void consumeRedis(String namespace, String identityHash, boolean authenticationBoundary,
                              List<Limit> limits) {
        try {
            for (int i = 0; i < limits.size(); i++) {
                Limit limit = limits.get(i);
                String key = redisKeyPrefix + ':' + namespace + ':' + i + ':' + identityHash;
                Long allowed = redisTemplate.execute(
                        CONSUME_SCRIPT,
                        List.of(key),
                        Long.toString(limit.capacity()),
                        Long.toString(limit.duration().toMillis()));
                if (!Long.valueOf(1L).equals(allowed)) {
                    throw new AppException(ErrorCode.RATE_LIMIT_EXCEEDED);
                }
            }
        } catch (AppException rateLimit) {
            throw rateLimit;
        } catch (RuntimeException redisFailure) {
            log.atError()
                    .addKeyValue("event", "rate_limit_backend_unavailable")
                    .addKeyValue("backend", "redis")
                    .addKeyValue("authenticationBoundary", authenticationBoundary)
                    .addKeyValue("errorType", redisFailure.getClass().getSimpleName())
                    .log("Rate limit backend unavailable");
            if (authenticationBoundary && authFailClosed) {
                throw new AppException(ErrorCode.RATE_LIMIT_BACKEND_UNAVAILABLE);
            }
        }
    }

    private void consumeLocal(String key, List<Limit> limits) {
        long now = Instant.now().getEpochSecond();
        Entry entry = buckets.compute(key, (ignored, existing) -> existing == null
                ? new Entry(localBucket(limits), now)
                : existing.touch(now));
        if (operations.incrementAndGet() % 256 == 0) {
            buckets.entrySet().removeIf(e -> now - e.getValue().lastSeenEpochSecond() > ENTRY_TTL_SECONDS);
        }
        if (!entry.bucket().tryConsume(1)) {
            throw new AppException(ErrorCode.RATE_LIMIT_EXCEEDED);
        }
    }

    private Bucket localBucket(List<Limit> limits) {
        var builder = Bucket.builder();
        for (Limit limit : limits) {
            builder.addLimit(Bandwidth.builder()
                    .capacity(limit.capacity())
                    .refillGreedy(limit.capacity(), limit.duration())
                    .build());
        }
        return builder.build();
    }

    private static List<Limit> one(long capacity, Duration duration) {
        return List.of(new Limit(capacity, duration));
    }

    private static List<Limit> dual(long firstCapacity, Duration firstDuration,
                                    long secondCapacity, Duration secondDuration) {
        return List.of(new Limit(firstCapacity, firstDuration), new Limit(secondCapacity, secondDuration));
    }

    private static String fingerprint(String identity) {
        String safe = identity == null ? "" : identity.trim().toLowerCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(safe.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record Limit(long capacity, Duration duration) {}

    private record Entry(Bucket bucket, long lastSeenEpochSecond) {
        Entry touch(long now) {
            return new Entry(bucket, now);
        }
    }
}
