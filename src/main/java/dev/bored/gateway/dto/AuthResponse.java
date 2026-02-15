package dev.bored.gateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Standardised auth response returned to the frontend.
 * <p>
 * Contains the JWT access token, refresh token, token type, and expiry.
 * Mirrors the relevant fields from Supabase's GoTrue response.
 * </p>
 *
 * @param accessToken  JWT access token
 * @param refreshToken opaque refresh token
 * @param tokenType    always "bearer"
 * @param expiresIn    seconds until the access token expires
 * @param email        authenticated user's email
 */
public record AuthResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn,
        String email
) {}
