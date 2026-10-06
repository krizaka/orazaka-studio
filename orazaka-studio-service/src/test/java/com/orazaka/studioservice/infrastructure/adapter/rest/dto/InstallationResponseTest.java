package com.orazaka.studioservice.infrastructure.adapter.rest.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.orazaka.studio.domain.model.InstallationStatus;
import com.orazaka.studioservice.domain.model.InstalledStudio;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InstallationResponseTest {

  private static final UUID ID = UUID.fromString("9f1c0a10-0000-4000-8000-000000000010");

  private static InstalledStudio installed(
      InstallationStatus status, String pinned, String latest) {
    return new InstalledStudio(
        ID,
        "k",
        "L",
        "studio",
        pinned,
        latest,
        status,
        Map.of(),
        Instant.parse("2026-08-05T10:00:00Z"),
        null);
  }

  @Test
  @DisplayName("An ACTIVE installation behind the catalogue reports UPGRADE_AVAILABLE on read")
  void derivesUpgradeAvailable() {
    var response =
        InstallationResponse.from(installed(InstallationStatus.ACTIVE, "1.0.0", "1.1.0"));

    assertThat(response.status()).isEqualTo(InstallationStatus.UPGRADE_AVAILABLE);
  }

  @Test
  @DisplayName("An up-to-date installation stays ACTIVE")
  void upToDateStaysActive() {
    var response =
        InstallationResponse.from(installed(InstallationStatus.ACTIVE, "1.1.0", "1.1.0"));

    assertThat(response.status()).isEqualTo(InstallationStatus.ACTIVE);
  }

  @Test
  @DisplayName("A PAUSED installation is not silently reported as upgradable — dunning wins")
  void pausedIsNotOverwritten() {
    var response =
        InstallationResponse.from(installed(InstallationStatus.PAUSED, "1.0.0", "1.1.0"));

    assertThat(response.status()).isEqualTo(InstallationStatus.PAUSED);
  }
}
