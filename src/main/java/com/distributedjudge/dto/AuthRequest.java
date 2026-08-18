package com.distributedjudge.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AuthRequest(
        @NotBlank @Email String email,
        @Size(max = 80) String displayName,
        @Size(max = 120) String password
) {
}
