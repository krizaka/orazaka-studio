package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

import com.krizaka.orazaka.studioservice.domain.model.BlueprintVersion;
import java.time.Instant;

/**
 * One row of the version history.
 *
 * <p>The changelog is the product surface of an explicit upgrade: an actor is asked to move their
 * pin, so they must be able to read what would change before they agree (ADR-034 §5).
 *
 * @param version semver
 * @param status DRAFT, PUBLISHED or DEPRECATED
 * @param estimatedCredits what one run of this version holds
 * @param changelog what changed against the previous version, may be {@code null}
 * @param publishedAt when it went live, {@code null} while it is a draft
 */
public record BlueprintVersionResponse(
    String version, String status, long estimatedCredits, String changelog, Instant publishedAt) {

  /**
   * Projects a version onto the wire.
   *
   * @param version the history row
   * @return the response
   */
  public static BlueprintVersionResponse from(BlueprintVersion version) {
    return new BlueprintVersionResponse(
        version.version(),
        version.status().name(),
        version.estimatedCredits(),
        version.changelog(),
        version.publishedAt());
  }
}
