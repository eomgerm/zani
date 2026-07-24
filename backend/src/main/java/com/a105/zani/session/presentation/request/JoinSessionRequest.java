package com.a105.zani.session.presentation.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record JoinSessionRequest(
        @NotBlank @Size(min = 8, max = 8) String inviteCode) {}
