package com.distributedjudge.dto;

public record MetricsSnapshot(
        long pending,
        long running,
        long completed,
        long accepted,
        long wrongAnswer,
        long timeLimitExceeded,
        long runtimeError,
        long rateLimitRejections
) {
}
