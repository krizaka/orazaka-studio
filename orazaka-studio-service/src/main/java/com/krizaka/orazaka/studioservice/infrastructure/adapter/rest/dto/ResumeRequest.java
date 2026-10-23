package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

import java.util.Objects;

/**
 * The body of {@code POST /runs/{id}/resume} (orazaka-studio#63).
 *
 * @param fromStep the id of the step to run again; the steps that do not depend on it keep their
 *     recorded outputs
 */
public record ResumeRequest(String fromStep) {

  /** Compact canonical constructor: a resume names its step. */
  public ResumeRequest {
    Objects.requireNonNull(fromStep, "fromStep must not be null");
    if (fromStep.isBlank()) {
      throw new IllegalArgumentException("fromStep must not be blank");
    }
  }
}
