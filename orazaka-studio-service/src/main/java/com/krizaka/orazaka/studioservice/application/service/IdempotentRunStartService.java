package com.krizaka.orazaka.studioservice.application.service;

import com.krizaka.orazaka.studioservice.domain.exception.IdempotencyKeyReusedException;
import com.krizaka.orazaka.studioservice.domain.exception.RunNotFoundException;
import com.krizaka.orazaka.studioservice.domain.model.RequestFingerprint;
import com.krizaka.orazaka.studioservice.domain.model.RunDetail;
import com.krizaka.orazaka.studioservice.domain.port.RunIdempotencyStore;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starts a run at most once per {@code Idempotency-Key} (ADR-083).
 *
 * <p>A run is paid for the moment it starts. A client whose connection dropped after the engine
 * accepted the request cannot tell whether a run exists, and retrying blind either duplicates the
 * work or loses it. With a key the retry is safe: the same key and the same request answer the run
 * that already exists.
 *
 * <p>The decision is taken inside the start's own transaction, after an advisory lock on the key,
 * so two parallel attempts take turns instead of both holding credits. A start that fails records
 * nothing and rolls back: the retry then starts for real.
 */
@Service
public class IdempotentRunStartService {

  private static final Pattern VALID_KEY = Pattern.compile("[\\x21-\\x7E]{1,255}");

  private final RunIdempotencyStore store;
  private final StudioRunService runs;

  public IdempotentRunStartService(RunIdempotencyStore store, StudioRunService runs) {
    this.store = Objects.requireNonNull(store, "RunIdempotencyStore cannot be null");
    this.runs = Objects.requireNonNull(runs, "StudioRunService cannot be null");
  }

  /**
   * The outcome of a start.
   *
   * @param run the run, new or replayed
   * @param replayed {@code true} when the key had already started it
   */
  public record Started(RunDetail run, boolean replayed) {}

  /**
   * Starts a run, or answers the one this key already started.
   *
   * @param scope what is run: {@code studio:<key>} or {@code installation:<id>}
   * @param actorId the opaque billable subject
   * @param inputs the actor's answers
   * @param key the caller's {@code Idempotency-Key}; {@code null} or blank means none
   * @param start the actual start, called only when the key has not started a run
   * @return the run and whether it is a replay
   * @throws IllegalArgumentException when the key is not 1 to 255 visible ASCII characters
   * @throws IdempotencyKeyReusedException when the key started a different request
   */
  @Transactional
  public Started start(
      String scope,
      String actorId,
      Map<String, Object> inputs,
      String key,
      Supplier<RunDetail> start) {
    if (key == null || key.isBlank()) {
      return new Started(start.get(), false);
    }
    if (!VALID_KEY.matcher(key).matches()) {
      throw new IllegalArgumentException(
          "Idempotency-Key must be 1 to 255 visible ASCII characters");
    }
    store.lock(actorId, key);
    String fingerprint = fingerprint(scope, inputs);
    var existing = store.find(actorId, key);
    if (existing.isPresent()) {
      if (!existing.get().fingerprint().equals(fingerprint)) {
        throw new IdempotencyKeyReusedException();
      }
      RunDetail run =
          runs.find(existing.get().runId(), actorId)
              .orElseThrow(() -> new RunNotFoundException(existing.get().runId()));
      return new Started(run, true);
    }
    RunDetail run = start.get();
    store.record(run.id(), actorId, key, fingerprint);
    return new Started(run, false);
  }

  /** SHA-256 of the request in canonical form: the order of the fields is not a difference. */
  String fingerprint(String scope, Map<String, Object> inputs) {
    return RequestFingerprint.of(scope, inputs);
  }
}
