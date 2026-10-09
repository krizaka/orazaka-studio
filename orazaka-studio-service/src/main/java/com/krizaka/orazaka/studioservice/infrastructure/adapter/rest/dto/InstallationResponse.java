package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

import com.krizaka.orazaka.studio.domain.model.InstallationStatus;
import com.krizaka.orazaka.studioservice.domain.model.InstalledStudio;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One "My Studios" card.
 *
 * <p>{@code status} is reported as {@code UPGRADE_AVAILABLE} when a newer published version exists,
 * even though the stored row still says {@code ACTIVE}. The database records what the actor chose;
 * this field records what the catalogue has since published, and computing it on read means a
 * publish lights up every existing installation without a migration that walks the table.
 *
 * @param id the installation's identity
 * @param studioKey the Studio installed
 * @param label the Studio's localised name
 * @param iconKey resolves in the design system's icon registry
 * @param pinnedVersion the version every run executes
 * @param latestVersion the newest published version, {@code null} when the Studio has none
 * @param status lifecycle, widened to UPGRADE_AVAILABLE when the pin is behind
 * @param config the actor's answers
 * @param installedAt when they installed it
 * @param lastRunAt when they last ran it, {@code null} until the first run
 */
public record InstallationResponse(
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

  /**
   * Projects an installation onto the wire, deriving the upgrade signal.
   *
   * @param installed the joined installation
   * @return the card
   */
  public static InstallationResponse from(InstalledStudio installed) {
    InstallationStatus effective =
        installed.status() == InstallationStatus.ACTIVE && installed.upgradeAvailable()
            ? InstallationStatus.UPGRADE_AVAILABLE
            : installed.status();
    return new InstallationResponse(
        installed.id(),
        installed.studioKey(),
        installed.label(),
        installed.iconKey(),
        installed.pinnedVersion(),
        installed.latestVersion(),
        effective,
        installed.config(),
        installed.installedAt(),
        installed.lastRunAt());
  }
}
