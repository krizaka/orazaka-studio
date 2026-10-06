package com.orazaka.studio.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One immutable, semver'd blueprint version as a bundle ships it.
 *
 * <p>The three schemas travel as raw JSON strings rather than parsed trees: {@code
 * studio_blueprint} stores them as {@code jsonb} and the interpreter parses them on read ({@code
 * BlueprintRepository}), so parsing here would mean parsing twice and, worse, risking a round-trip
 * that silently reformats a published blueprint the immutability trigger is meant to freeze.
 *
 * @param version the semver of this version
 * @param status DRAFT, PUBLISHED or DEPRECATED as the bundle ships it
 * @param definition the steps and outputs, as JSON
 * @param inputSchema the JSON Schema 2020-12 that drives the run form
 * @param configSchema what is asked once at install time
 * @param estimatedCredits the hold taken for one run
 * @param changelog what changed in this version
 * @param createdBy who authored it
 */
public record PackBlueprint(
    String version,
    BlueprintStatus status,
    String definition,
    String inputSchema,
    String configSchema,
    long estimatedCredits,
    String changelog,
    String createdBy) {

  private static final Pattern SEMVER = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");

  /** Compact canonical constructor enforcing the blueprint's invariants (ERR-106). */
  public PackBlueprint {
    if (version == null || !SEMVER.matcher(version).matches()) {
      throw new IllegalArgumentException("blueprint version must be semver: " + version);
    }
    Objects.requireNonNull(definition, "definition must not be null");
    Objects.requireNonNull(inputSchema, "inputSchema must not be null");
    if (estimatedCredits < 0) {
      throw new IllegalArgumentException("estimatedCredits must be >= 0");
    }
    status = status == null ? BlueprintStatus.DRAFT : status;
    configSchema = configSchema == null || configSchema.isBlank() ? "{}" : configSchema;
    createdBy = createdBy == null || createdBy.isBlank() ? "system" : createdBy;
  }
}
