package com.krizaka.orazaka.studioservice.domain.model;

import com.krizaka.orazaka.studio.domain.model.RunStepStatus;
import java.util.Objects;

/**
 * One node of a run as the timeline renders it.
 *
 * <p>{@code jobId} is exposed deliberately: it is what lets support open a failing step in the
 * existing jobs dashboard instead of asking the user to reproduce it (ADR-034 §14).
 *
 * @param stepId the blueprint step's id
 * @param ordinal the fan-out index; {@code 0} for a single-shot step
 * @param jobId the job it was submitted as, {@code null} before submission or for a skipped step
 * @param status where this node sits in its lifecycle
 * @param attempts how many times it has been submitted
 * @param error the failure message, {@code null} unless it failed
 */
public record RunStepView(
    String stepId, int ordinal, String jobId, RunStepStatus status, int attempts, String error) {

  /** Compact canonical constructor enforcing the contract's invariants (ERR-106). */
  public RunStepView {
    if (stepId == null || stepId.isBlank()) {
      throw new IllegalArgumentException("stepId must not be blank");
    }
    Objects.requireNonNull(status, "status must not be null");
    if (ordinal < 0) {
      throw new IllegalArgumentException("ordinal must be >= 0, was: " + ordinal);
    }
  }
}
