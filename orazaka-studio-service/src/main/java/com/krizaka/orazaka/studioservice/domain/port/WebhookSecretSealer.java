package com.krizaka.orazaka.studioservice.domain.port;

import java.util.UUID;

/**
 * Seals a webhook signing secret for storage at rest and opens it again. A sealed value is bound to
 * the endpoint it was sealed for: it opens nowhere else.
 */
public interface WebhookSecretSealer {

  /**
   * Seals a secret.
   *
   * @param endpointId the endpoint the secret belongs to, bound into the sealed value
   * @param plaintext the secret
   * @return the sealed form, safe to store
   */
  String seal(UUID endpointId, String plaintext);

  /**
   * Opens a sealed secret.
   *
   * @param endpointId the endpoint it belongs to
   * @param sealed the stored form
   * @return the secret
   * @throws IllegalStateException when the value does not open (another endpoint, a tampered value,
   *     a master key this deployment no longer holds) - never a fallback to the stored form
   */
  String open(UUID endpointId, String sealed);

  /**
   * Whether a stored value is already in the sealed form.
   *
   * @param stored a stored value
   * @return {@code true} for a sealed value, {@code false} for a clear one
   */
  boolean isSealed(String stored);
}
