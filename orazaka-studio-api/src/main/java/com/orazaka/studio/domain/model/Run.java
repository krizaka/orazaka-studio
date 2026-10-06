package com.orazaka.studio.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One execution of an installation, fanned out into jobs on the existing job plane.
 *
 * <p>A run holds credits for its whole duration: one hold for the blueprint's estimate, settled
 * against measured consumption or released on failure. One hold per run rather than per step is a
 * product decision — discovering mid-run that step 5 is unaffordable is worse than a slightly
 * conservative estimate, and the measured settle corrects the drift anyway (ADR-034 §4).
 *
 * @param id this run's identity, and the id the UI subscribes to for live progress
 * @param installationId the installation this run executes
 * @param actorId OPAQUE billable subject; every read and write filters on it, which is the whole
 *     tenant-isolation story
 * @param studioKey denormalised so a run reads without joining the installation
 * @param blueprintVersion denormalised on purpose: a run stays reproducible after the installation
 *     is upgraded, so "re-run" replays the version that actually produced the result
 * @param status where this run sits in its lifecycle
 * @param holdId OPAQUE reference into billing; {@code null} before the hold is acquired and for a
 *     zero-cost preview run
 * @param correlationId ties the run, its jobs and its ledger entries into one traceable unit
 * @param inputs the actor's answers to the blueprint's input schema, retained for reproducibility;
 *     defensively copied
 * @param startedAt when the run was accepted
 * @param finishedAt when it reached a terminal state, {@code null} while it has not — which is also
 *     the sweeper's predicate
 */
public record Run(
    UUID id,
    UUID installationId,
    String actorId,
    String studioKey,
    String blueprintVersion,
    RunStatus status,
    String holdId,
    String correlationId,
    Map<String, Object> inputs,
    Instant startedAt,
    Instant finishedAt) {

  /** Compact canonical constructor enforcing the contract's invariants (ERR-106). */
  public Run {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(installationId, "installationId must not be null");
    if (actorId == null || actorId.isBlank()) {
      throw new IllegalArgumentException("actorId must not be blank");
    }
    if (studioKey == null || studioKey.isBlank()) {
      throw new IllegalArgumentException("studioKey must not be blank");
    }
    if (blueprintVersion == null || blueprintVersion.isBlank()) {
      throw new IllegalArgumentException("blueprintVersion must not be blank");
    }
    Objects.requireNonNull(status, "status must not be null");
    if (correlationId == null || correlationId.isBlank()) {
      throw new IllegalArgumentException("correlationId must not be blank");
    }
    Objects.requireNonNull(startedAt, "startedAt must not be null");
    inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
  }
}
