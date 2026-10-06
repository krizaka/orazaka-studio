package com.orazaka.studioservice.domain.model;

import com.orazaka.studio.domain.model.BlueprintStatus;
import java.time.Instant;
import java.util.Objects;

/**
 * One row of a Studio's version history — the shape the version list and the upgrade banner read.
 *
 * <p>Deliberately lighter than the Tier-1 {@code Blueprint}: listing versions must not parse and
 * validate every DAG in the history, and the only questions a history answers are "which versions
 * exist, what changed, and what does a run cost".
 *
 * @param studioKey the Studio this version belongs to
 * @param version semver
 * @param status where this version sits in its publication lifecycle
 * @param estimatedCredits what one run of it holds
 * @param changelog what changed against the previous version
 * @param publishedAt when it went live, {@code null} while it is a draft
 */
public record BlueprintVersion(
    String studioKey,
    String version,
    BlueprintStatus status,
    long estimatedCredits,
    String changelog,
    Instant publishedAt) {

  /** Compact canonical constructor enforcing the contract's invariants (ERR-106). */
  public BlueprintVersion {
    if (studioKey == null || studioKey.isBlank()) {
      throw new IllegalArgumentException("studioKey must not be blank");
    }
    if (version == null || version.isBlank()) {
      throw new IllegalArgumentException("version must not be blank");
    }
    Objects.requireNonNull(status, "status must not be null");
    if (estimatedCredits < 0) {
      throw new IllegalArgumentException("estimatedCredits must be >= 0, was: " + estimatedCredits);
    }
  }
}
