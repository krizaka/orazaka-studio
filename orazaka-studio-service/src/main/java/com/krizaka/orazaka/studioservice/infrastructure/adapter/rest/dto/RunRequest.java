package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

import java.util.Map;

/**
 * The run-form submission: the actor's answers to the blueprint's input schema.
 *
 * <p>Validated server-side before any credit is held — the generated client form is convenience,
 * never enforcement (ADR-034 §18).
 *
 * @param inputs the answers, keyed by input-schema property; {@code null} is read as none
 */
public record RunRequest(Map<String, Object> inputs) {

  /** Compact canonical constructor: absent input is empty, never null (ERR-106). */
  public RunRequest {
    inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
  }
}
