package com.distributedjudge.dto;

public record ProblemResponse(
        Long id,
        String title,
        String statement,
        String sampleInput,
        String sampleOutput,
        String difficulty,
        int hiddenTests
) {
}
