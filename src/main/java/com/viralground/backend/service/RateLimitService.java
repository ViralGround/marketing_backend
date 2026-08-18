package com.viralground.backend.service;

import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class RateLimitService {

    private final ConcurrentHashMap<String, Entry> buckets = new ConcurrentHashMap<>();
    private final AtomicLong operations = new AtomicLong();
    private static final long ENTRY_TTL_SECONDS = 3600;

    public void consumeLoginByIp(String ip) {
        consume("login:ip:" + ip, () -> Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(10).refillGreedy(10, Duration.ofMinutes(1)).build())
                .addLimit(Bandwidth.builder().capacity(30).refillGreedy(30, Duration.ofMinutes(10)).build())
                .build());
    }

    public void consumeRequestCodeByIp(String ip) {
        consume("reqcode:ip:" + ip, () -> Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(3).refillGreedy(3, Duration.ofMinutes(1)).build())
                .build());
    }

    public void consumeRequestCodeByEmail(String email) {
        consume("reqcode:email:" + email, () -> Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(3).refillGreedy(3, Duration.ofMinutes(1)).build())
                .build());
    }

    public void consumeVerifyCodeByIp(String ip) {
        consume("verifycode:ip:" + ip, () -> Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(20).refillGreedy(20, Duration.ofMinutes(1)).build())
                .build());
    }

    public void consumePasswordResetRequestByIp(String ip) {
        consume("pwreset:req:ip:" + ip, () -> Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(3).refillGreedy(3, Duration.ofMinutes(1)).build())
                .build());
    }

    public void consumePasswordResetRequestByEmail(String email) {
        consume("pwreset:req:email:" + email, () -> Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(3).refillGreedy(3, Duration.ofMinutes(1)).build())
                .build());
    }

    public void consumePasswordResetConfirmByIp(String ip) {
        consume("pwreset:confirm:ip:" + ip, () -> Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(10).refillGreedy(10, Duration.ofMinutes(1)).build())
                .build());
    }

    public void consumeContactByIp(String ip) {
        consume("contact:ip:" + ip, () -> Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(3).refillGreedy(3, Duration.ofMinutes(10)).build())
                .build());
    }

    private void consume(String key, Supplier<Bucket> bucketFactory) {
        long now = Instant.now().getEpochSecond();
        Entry entry = buckets.compute(key, (k, existing) -> existing == null
                ? new Entry(bucketFactory.get(), now)
                : existing.touch(now));
        if (operations.incrementAndGet() % 256 == 0) {
            buckets.entrySet().removeIf(e -> now - e.getValue().lastSeenEpochSecond() > ENTRY_TTL_SECONDS);
        }
        Bucket bucket = entry.bucket();
        if (!bucket.tryConsume(1)) {
            throw new AppException(ErrorCode.RATE_LIMIT_EXCEEDED);
        }
    }

    private record Entry(Bucket bucket, long lastSeenEpochSecond) {
        Entry touch(long now) {
            return new Entry(bucket, now);
        }
    }
}
