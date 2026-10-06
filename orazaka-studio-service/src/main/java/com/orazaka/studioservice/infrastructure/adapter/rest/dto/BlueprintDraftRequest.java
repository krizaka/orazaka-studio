package com.orazaka.studioservice.infrastructure.adapter.rest.dto;

import java.util.Objects;

/**
 * An authored blueprint version, as the Studio Builder submits it.
 *
 * <p>The schemas travel as raw JSON strings rather than parsed objects: they are JSON Schema
 * documents the client authored and the client renders forms from, so re-serialising them through a
 * typed model here would be a lossy round trip for no gain.
 *
 * @param definition the steps-and-outputs JSON
 * @param inputSchema the run-form JSON Schema
 * @param configSchema the install-dialog JSON Schema; blank is read as none
 * @param estimatedCredits what one run should hold
 * @param changelog what changed against the previous version
 */
public record BlueprintDraftRequest(
    String definition,
    String inputSchema,
    String configSchema,
    long estimatedCredits,
    String changelog) {

  /** Compact canonical constructor enforcing the contract's invariants (ERR-106). */
  public BlueprintDraftRequest {
    Objects.requireNonNull(definition, "definition must not be null");
    if (definition.isBlank()) {
      throw new IllegalArgumentException("definition must not be blank");
    }
    if (inputSchema == null || inputSchema.isBlank()) {
      throw new IllegalArgumentException("inputSchema must not be blank");
    }
    if (estimatedCredits < 0) {
      throw new IllegalArgumentException("estimatedCredits must be >= 0");
    }
  }
}
