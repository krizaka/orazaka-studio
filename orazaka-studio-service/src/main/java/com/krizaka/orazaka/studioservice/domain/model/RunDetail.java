package com.krizaka.orazaka.studioservice.domain.model;

import com.krizaka.orazaka.studio.domain.model.RunStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One run with everything the run screen shows.
 *
 * <p>Steps arrive separately and are attached with {@link #withSteps}, because the history list
 * needs runs without their steps and loading them anyway would be an N+1 across the whole page
 * (ERR-109).
 *
 * @param id the run's identity
 * @param installationId the installation it executes
 * @param studioKey the Studio
 * @param blueprintVersion the version it ran — denormalised, so the run stays reproducible
 * @param status where the run sits in its lifecycle
 * @param holdId the credit reservation, {@code null} when unmetered
 * @param outputs the declared artefacts with their presentation, empty until it succeeds
 * @param errorMessage why it failed, {@code null} otherwise
 * @param startedAt when it was accepted
 * @param finishedAt when it reached a terminal state, {@code null} while running
 * @param steps the per-node states; empty in a history listing
 */
public record RunDetail(
    UUID id,
    UUID installationId,
    String studioKey,
    String blueprintVersion,
    RunStatus status,
    String holdId,
    List<RunArtefact> outputs,
    String errorMessage,
    Instant startedAt,
    Instant finishedAt,
    List<RunStepView> steps) {

  /** Compact canonical constructor enforcing the contract's invariants (ERR-106). */
  public RunDetail {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(startedAt, "startedAt must not be null");
    outputs = outputs == null ? List.of() : List.copyOf(outputs);
    steps = steps == null ? List.of() : List.copyOf(steps);
  }

  /**
   * The same run with its artefacts resolved against the blueprint that declared them.
   *
   * @param artefacts the outputs, carrying their label and rendering type
   * @return a copy carrying them
   */
  public RunDetail withOutputs(List<RunArtefact> artefacts) {
    return new RunDetail(
        id,
        installationId,
        studioKey,
        blueprintVersion,
        status,
        holdId,
        artefacts,
        errorMessage,
        startedAt,
        finishedAt,
        steps);
  }

  /**
   * The same run with its per-node states attached.
   *
   * @param loaded the steps
   * @return a copy carrying them
   */
  public RunDetail withSteps(List<RunStepView> loaded) {
    return new RunDetail(
        id,
        installationId,
        studioKey,
        blueprintVersion,
        status,
        holdId,
        outputs,
        errorMessage,
        startedAt,
        finishedAt,
        loaded);
  }
}
