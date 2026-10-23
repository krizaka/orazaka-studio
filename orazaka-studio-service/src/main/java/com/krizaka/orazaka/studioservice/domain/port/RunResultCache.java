package com.krizaka.orazaka.studioservice.domain.port;

import java.util.Optional;
import java.util.UUID;

/**
 * The exact result cache (orazaka-studio#61): the run that already answered the very same request.
 *
 * <p>The memory is the run row itself ({@code studio_run.input_fingerprint}): a result purged by
 * retention (ADR-065) is a result the cache no longer has, and a cached answer never outlives the
 * run it copies.
 */
public interface RunResultCache {

  /**
   * The latest succeeded, original run of this actor for this Studio version and request.
   *
   * @param actorId the opaque billable subject — the cache never crosses actors
   * @param studioKey the Studio
   * @param version the blueprint version: any change of version is a different key
   * @param fingerprint the request's fingerprint (inputs and the installation's configuration)
   * @return the run to answer from, or empty
   */
  Optional<UUID> findSucceeded(
      String actorId, String studioKey, String version, String fingerprint);
}
