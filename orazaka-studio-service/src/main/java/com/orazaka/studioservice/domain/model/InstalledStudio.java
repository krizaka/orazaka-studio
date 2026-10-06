package com.orazaka.studioservice.domain.model;

import com.orazaka.studio.domain.model.InstallationStatus;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * An installation joined to the catalogue row it points at — what "My Studios" renders.
 *
 * <p>Distinct from the Tier-1 {@code Installation}, which is the pure workspace record: a card
 * needs the Studio's label and icon, and fetching those per row would turn one screen into N+1
 * queries (ERR-109). Carrying {@code latestVersion} alongside {@code pinnedVersion} is what lets
 * the card show an upgrade banner without a second lookup — and the two being different is the
 * <b>only</b> signal that an upgrade exists, since a pin is never migrated silently (ADR-034 §5).
 *
 * @param id the installation's identity
 * @param studioKey the Studio installed
 * @param label the Studio's localised name
 * @param iconKey resolves in the design system's icon registry
 * @param pinnedVersion the version every run of this installation executes
 * @param latestVersion the newest published version, {@code null} when the Studio has none
 * @param status where this installation sits in its lifecycle
 * @param config the actor's answers to the config schema; defensively copied
 * @param installedAt when the actor installed it
 * @param lastRunAt when they last ran it, {@code null} until the first run
 */
public record InstalledStudio(
    UUID id,
    String studioKey,
    String label,
    String iconKey,
    String pinnedVersion,
    String latestVersion,
    InstallationStatus status,
    Map<String, String> config,
    Instant installedAt,
    Instant lastRunAt) {

  /** Compact canonical constructor enforcing the contract's invariants (ERR-106). */
  public InstalledStudio {
    Objects.requireNonNull(id, "id must not be null");
    if (studioKey == null || studioKey.isBlank()) {
      throw new IllegalArgumentException("studioKey must not be blank");
    }
    if (pinnedVersion == null || pinnedVersion.isBlank()) {
      throw new IllegalArgumentException("pinnedVersion must not be blank");
    }
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(installedAt, "installedAt must not be null");
    config = config == null ? Map.of() : Map.copyOf(config);
  }

  /**
   * Whether a newer published version exists than the one this installation pins.
   *
   * <p>Asked by the card and by the upgrade banner, so it lives on the record rather than being
   * recomputed at each call site (ERR-127) — two implementations of "is there an upgrade" would
   * eventually disagree about a Studio whose latest version was withdrawn.
   *
   * @return whether an explicit upgrade is available
   */
  public boolean upgradeAvailable() {
    return latestVersion != null && !latestVersion.equals(pinnedVersion);
  }
}
