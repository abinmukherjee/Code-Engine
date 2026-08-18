package com.distributedjudge.dto;

import com.distributedjudge.model.AuthMode;
import java.time.Instant;

public record UserResponse(
        Long id,
        String email,
        String displayName,
        AuthMode authMode,
        Instant lastLoginAt
) {
}
