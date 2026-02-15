package dev.bored.gateway.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for token refresh.
 *
 * @param refreshToken the refresh token issued during login/signup
 */
public record RefreshRequest(
        @NotBlank String refreshToken
) {}
