package dev.bored.gateway.dto;

/**
 * Response containing the OAuth provider redirect URL.
 * <p>
 * The frontend opens this URL in a popup/new tab. After the user authenticates
 * with the provider, Supabase redirects back to the configured callback URL.
 * </p>
 *
 * @param url the full OAuth authorization URL to redirect the user to
 */
public record OAuthUrlResponse(String url) {}
