package com.viralground.backend.service;

import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Service
public class RateLimitService {

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

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
                .addLimit(Bandwidth.builder().capacity(1).refillGreedy(1, Duration.ofMinutes(1)).build())
                .build());
    }

    public void consumeVerifyCodeByIp(String ip) {
        consume("verifycode:ip:" + ip, () -> Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(20).refillGreedy(20, Duration.ofMinutes(1)).build())
                .build());
    }

    private void consume(String key, Supplier<Bucket> bucketFactory) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> bucketFactory.get());
        if (!bucket.tryConsume(1)) {
            throw new AppException(ErrorCode.RATE_LIMIT_EXCEEDED);
        }
    }
}
