package dev.bored.gateway.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for email/password login.
 *
 * @param email    user email address
 * @param password user password
 */
public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password
) {}
