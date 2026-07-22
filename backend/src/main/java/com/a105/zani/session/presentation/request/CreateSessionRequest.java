package com.a105.zani.session.presentation.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSessionRequest(
        @NotBlank
        @Size(max = 100)
        String title) {
}
