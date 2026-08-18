package com.distributedjudge.dto;

import com.distributedjudge.model.Language;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SubmissionRequest(
        String userId,
        @NotNull Long problemId,
        @NotNull Language language,
        @NotBlank @Size(max = 8000) String sourceCode
) {
}
