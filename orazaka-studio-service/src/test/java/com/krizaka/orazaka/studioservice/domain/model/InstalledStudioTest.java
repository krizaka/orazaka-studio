package com.krizaka.orazaka.studioservice.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.krizaka.orazaka.studio.domain.model.InstallationStatus;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InstalledStudioTest {

  private static final UUID ID = UUID.fromString("9f1c0a10-0000-4000-8000-000000000010");
  private static final Instant INSTALLED = Instant.parse("2026-08-05T10:00:00Z");

  private static InstalledStudio installed(String pinned, String latest) {
    return new InstalledStudio(
        ID,
        "trade-showcase",
        "Vitrine Artisan",
        "studio",
        pinned,
        latest,
        InstallationStatus.ACTIVE,
        Map.of("tone", "premium"),
        INSTALLED,
        null);
  }

  @Test
  @DisplayName("An upgrade exists only when the newest published version differs from the pin")
  void upgradeAvailableWhenPinIsBehind() {
    assertThat(installed("1.0.0", "1.1.0").upgradeAvailable()).isTrue();
    assertThat(installed("1.1.0", "1.1.0").upgradeAvailable()).isFalse();
  }

  @Test
  @DisplayName("A studio with no published version offers no upgrade")
  void noUpgradeWithoutAPublishedVersion() {
    assertThat(installed("1.0.0", null).upgradeAvailable()).isFalse();
  }

  @Test
  @DisplayName("Config is copied so a stored installation cannot change under its runs")
  void copiesConfig() {
    Map<String, String> source = new HashMap<>(Map.of("tone", "premium"));
    InstalledStudio studio =
        new InstalledStudio(
            ID,
            "k",
            "L",
            "studio",
            "1.0.0",
            "1.0.0",
            InstallationStatus.ACTIVE,
            source,
            INSTALLED,
            null);

    source.put("tone", "direct");

    assertThat(studio.config().get("tone")).isEqualTo("premium");
  }

  @Test
  @DisplayName("Rejects a missing identity, pin or status")
  void rejectsIncompleteIdentity() {
    assertThatThrownBy(
            () ->
                new InstalledStudio(
                    null,
                    "k",
                    "L",
                    "i",
                    "1.0.0",
                    null,
                    InstallationStatus.ACTIVE,
                    Map.of(),
                    INSTALLED,
                    null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new InstalledStudio(
                    ID,
                    " ",
                    "L",
                    "i",
                    "1.0.0",
                    null,
                    InstallationStatus.ACTIVE,
                    Map.of(),
                    INSTALLED,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new InstalledStudio(
                    ID,
                    "k",
                    "L",
                    "i",
                    "",
                    null,
                    InstallationStatus.ACTIVE,
                    Map.of(),
                    INSTALLED,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new InstalledStudio(
                    ID, "k", "L", "i", "1.0.0", null, null, Map.of(), INSTALLED, null))
        .isInstanceOf(NullPointerException.class);
  }
}
