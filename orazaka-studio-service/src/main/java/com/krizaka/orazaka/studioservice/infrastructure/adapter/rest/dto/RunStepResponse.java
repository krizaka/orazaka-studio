package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

import com.krizaka.orazaka.studio.domain.model.RunStepStatus;
import com.krizaka.orazaka.studioservice.domain.model.RunStepView;

/**
 * One row of the live run timeline.
 *
 * @param stepId the blueprint step's id
 * @param ordinal the fan-out index
 * @param jobId the job it runs as — what support opens in the jobs dashboard
 * @param status where this node sits
 * @param attempts how many submissions it took
 * @param error the failure message, {@code null} unless it failed
 * @param startedAt when the step was dispatched
 * @param estimateMs the measured median wall time of this step, or {@code null}
 * @param estimateSamples how many past runs that median is made of
 */
public record RunStepResponse(
    String stepId,
    int ordinal,
    String jobId,
    RunStepStatus status,
    int attempts,
    String error,
    java.time.Instant startedAt,
    Long estimateMs,
    int estimateSamples) {

  /**
   * Projects a step onto the wire.
   *
   * @param view the step
   * @return the response
   */
  public static RunStepResponse from(RunStepView view) {
    return new RunStepResponse(
        view.stepId(),
        view.ordinal(),
        view.jobId(),
        view.status(),
        view.attempts(),
        view.error(),
        view.startedAt(),
        view.estimateMs(),
        view.estimateSamples());
  }
}
