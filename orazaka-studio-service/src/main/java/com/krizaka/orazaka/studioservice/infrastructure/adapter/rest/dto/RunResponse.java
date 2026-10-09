package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

import com.krizaka.orazaka.studio.domain.model.RunStatus;
import com.krizaka.orazaka.studioservice.domain.model.RunDetail;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One run, as the run screen reads it.
 *
 * <p>{@code holdId} is deliberately absent: it is an opaque billing reference of no use to a client
 * and every field a response carries is a field an attacker can correlate.
 *
 * @param id the run
 * @param installationId the installation it executes, or {@code null} for a TOOLKIT run, whose
 *     installation is derived and has no row (ADR-061)
 * @param studioKey the Studio
 * @param blueprintVersion the version it ran
 * @param status where the run sits
 * @param outputs the declared artefacts with their presentation, empty until it succeeds
 * @param errorMessage why it failed, {@code null} otherwise
 * @param startedAt when it was accepted
 * @param finishedAt when it ended, {@code null} while running
 * @param steps the per-node states
 */
public record RunResponse(
    UUID id,
    UUID installationId,
    String studioKey,
    String blueprintVersion,
    RunStatus status,
    List<RunArtefactResponse> outputs,
    String errorMessage,
    Instant startedAt,
    Instant finishedAt,
    List<RunStepResponse> steps) {

  /**
   * Projects a run onto the wire.
   *
   * @param run the run
   * @return the response
   */
  public static RunResponse from(RunDetail run) {
    return new RunResponse(
        run.id(),
        run.installationId(),
        run.studioKey(),
        run.blueprintVersion(),
        run.status(),
        run.outputs().stream().map(RunArtefactResponse::from).toList(),
        run.errorMessage(),
        run.startedAt(),
        run.finishedAt(),
        run.steps().stream().map(RunStepResponse::from).toList());
  }
}
