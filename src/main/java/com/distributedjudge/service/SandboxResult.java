package com.distributedjudge.service;

import com.distributedjudge.model.Verdict;

public record SandboxResult(
        Verdict verdict,
        String stdout,
        String stderr,
        long runtimeMs,
        int memoryMb
) {
}
