package com.distributedjudge.service;

import com.distributedjudge.config.JudgeProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

@Service
@Profile("!worker")
public class RateLimiterService {
    private final JudgeProperties.RateLimit properties;
    private final Clock clock = Clock.systemUTC();
    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List> rateLimiterScript;
    private final Counter rejections;

    public RateLimiterService(
            JudgeProperties judgeProperties,
            MeterRegistry meterRegistry,
            StringRedisTemplate redisTemplate,
            RedisScript<List> rateLimiterScript
    ) {
        this.properties = judgeProperties.getRateLimit();
        this.redisTemplate = redisTemplate;
        this.rateLimiterScript = rateLimiterScript;
        this.rejections = Counter.builder("judge_rate_limit_rejections")
                .description("Number of requests rejected by the rate limiter")
                .register(meterRegistry);
    }

    public RateLimitResult checkLimit(String userId, int requestWeight) {
        double refillPerMs = properties.getRefillPerMinute() / 60_000.0;
        long windowMs = properties.getSlidingWindowSeconds() * 1000L;

        List<?> result = redisTemplate.execute(
                rateLimiterScript,
                List.of("rl:bucket:" + userId, "rl:window:" + userId),
                String.valueOf(clock.millis()),
                String.valueOf(properties.getCapacity()),
                String.valueOf(refillPerMs),
                String.valueOf(requestWeight),
                String.valueOf(windowMs),
                String.valueOf(properties.getSlidingWindowLimit())
        );

        boolean allowed = Long.parseLong(String.valueOf(result.get(0))) == 1;
        long retryAfterSeconds = Long.parseLong(String.valueOf(result.get(1)));

        if (!allowed) {
            rejections.increment();
        }
        return new RateLimitResult(allowed, retryAfterSeconds);
    }

    public long rejections() {
        return (long) rejections.count();
    }
}
