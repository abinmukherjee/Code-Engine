package com.distributedjudge.dto;

import java.time.Instant;

public record SubmissionAcceptedResponse(
        Long submissionId,
        String statusUrl,
        String queue,
        Instant acceptedAt
) {
}
