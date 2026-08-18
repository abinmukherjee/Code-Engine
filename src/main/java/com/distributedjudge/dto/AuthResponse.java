package com.distributedjudge.dto;

import com.distributedjudge.model.AuthMode;

public record AuthResponse(
        Long userId,
        String email,
        String displayName,
        AuthMode authMode,
        String token,
        boolean newUser
) {
}
