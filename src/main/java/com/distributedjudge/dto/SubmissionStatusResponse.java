package com.distributedjudge.dto;

import com.distributedjudge.model.Language;
import com.distributedjudge.model.SubmissionState;
import com.distributedjudge.model.Verdict;
import java.time.Instant;

public record SubmissionStatusResponse(
        Long id,
        String userId,
        Long problemId,
        String problemTitle,
        Language language,
        SubmissionState state,
        Verdict verdict,
        long runtimeMs,
        int memoryMb,
        String stdout,
        String stderr,
        Instant createdAt,
        Instant updatedAt
) {
}
