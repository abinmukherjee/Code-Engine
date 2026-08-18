package com.distributedjudge.service;

public record RateLimitResult(boolean allowed, long retryAfterSeconds) {
}
