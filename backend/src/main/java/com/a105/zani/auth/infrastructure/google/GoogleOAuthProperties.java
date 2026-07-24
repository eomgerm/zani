package com.a105.zani.auth.infrastructure.google;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "google.oauth")
public record GoogleOAuthProperties(@NotBlank String clientId) {}
