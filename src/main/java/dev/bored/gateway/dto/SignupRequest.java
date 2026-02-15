package dev.bored.gateway.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for email/password signup.
 *
 * @param email    user email address
 * @param password user password (minimum 6 characters — Supabase default)
 */
public record SignupRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 6, message = "Password must be at least 6 characters") String password
) {}
