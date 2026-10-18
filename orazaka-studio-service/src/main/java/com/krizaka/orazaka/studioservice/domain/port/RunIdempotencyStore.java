package com.krizaka.orazaka.studioservice.domain.port;

import java.util.Optional;
import java.util.UUID;

/**
 * Remembers which run an {@code Idempotency-Key} started (ADR-083).
 *
 * <p>The memory is the run row itself: nothing here outlives the run it points at, so the retention
 * of a run (ADR-065) is the retention of its key, and a key can never name a run that no longer
 * exists.
 */
public interface RunIdempotencyStore {

  /**
   * Serialises concurrent requests carrying the same key until the current transaction ends.
   *
   * @param actorId the opaque billable subject
   * @param key the caller's key
   */
  void lock(String actorId, String key);

  /**
   * The run this actor's key already started.
   *
   * @param actorId the opaque billable subject
   * @param key the caller's key
   * @return the run and the fingerprint of the request that started it, or empty
   */
  Optional<Recorded> find(String actorId, String key);

  /**
   * Attaches a key to the run it started.
   *
   * @param runId the run
   * @param actorId the opaque billable subject
   * @param key the caller's key
   * @param fingerprint the fingerprint of the request
   */
  void record(UUID runId, String actorId, String key, String fingerprint);

  /**
   * What a key remembers.
   *
   * @param runId the run it started
   * @param fingerprint the fingerprint of the request that started it
   */
  record Recorded(UUID runId, String fingerprint) {}
}
