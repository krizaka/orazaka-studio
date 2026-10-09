package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.krizaka.orazaka.studio.domain.model.InstallationStatus;
import com.krizaka.orazaka.studioservice.application.service.StudioInstallationService;
import com.krizaka.orazaka.studioservice.domain.exception.InstallationNotFoundException;
import com.krizaka.orazaka.studioservice.domain.model.InstalledStudio;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto.ConfigUpdateRequest;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto.InstallRequest;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto.InstallationResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

class StudioInstallationControllerTest {

  private static final String ACTOR = "550e8400-e29b-41d4-a716-446655440002";
  private static final UUID INSTALLATION = UUID.fromString("9f1c0a10-0000-4000-8000-000000000010");
  private static final Jwt ACTOR_JWT =
      Jwt.withTokenValue("t").header("alg", "HS256").subject(ACTOR).build();

  private final StudioInstallationService installationService =
      mock(StudioInstallationService.class);
  private final StudioInstallationController controller =
      new StudioInstallationController(installationService);

  private static InstalledStudio installed(String pinned, String latest) {
    return new InstalledStudio(
        INSTALLATION,
        "trade-showcase",
        "Vitrine Artisan",
        "studio",
        pinned,
        latest,
        InstallationStatus.ACTIVE,
        Map.of("tone", "premium"),
        Instant.parse("2026-08-05T10:00:00Z"),
        null);
  }

  @Test
  @DisplayName("Install answers 201 with the stored installation")
  void installReturnsCreated() {
    when(installationService.install(eq("trade-showcase"), eq(ACTOR), any(), anyString(), any()))
        .thenReturn(installed("1.0.0", "1.0.0"));

    ResponseEntity<InstallationResponse> response =
        controller.install(
            "trade-showcase", new InstallRequest(Map.of("tone", "premium")), "fr", ACTOR_JWT);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody().pinnedVersion()).isEqualTo("1.0.0");
  }

  @Test
  @DisplayName("Install tolerates an absent body — a Studio with no config needs no dialog")
  void installToleratesNoBody() {
    when(installationService.install(eq("trade-showcase"), eq(ACTOR), any(), anyString(), any()))
        .thenReturn(installed("1.0.0", "1.0.0"));

    controller.install("trade-showcase", null, "fr", ACTOR_JWT);

    // The fifth argument is the consent declaration, null for a STANDARD pack that requires
    // none — and the assertion says so rather than matching anything (ADR-055 §3).
    verify(installationService)
        .install(
            eq("trade-showcase"),
            eq(ACTOR),
            eq(Map.of()),
            eq("fr"),
            org.mockito.ArgumentMatchers.isNull());
  }

  @Test
  @DisplayName("My Studios reports a pin behind the catalogue as UPGRADE_AVAILABLE")
  void listDerivesUpgradeSignal() {
    when(installationService.listFor(ACTOR)).thenReturn(List.of(installed("1.0.0", "1.1.0")));

    List<InstallationResponse> installations = controller.list(ACTOR_JWT);

    assertThat(installations)
        .singleElement()
        .extracting(InstallationResponse::status)
        .isEqualTo(InstallationStatus.UPGRADE_AVAILABLE);
  }

  @Test
  @DisplayName("An installation the caller does not own is 404, not 403 — no existence disclosure")
  void foreignInstallationIsNotFound() {
    when(installationService.find(INSTALLATION, ACTOR)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.find(INSTALLATION, ACTOR_JWT))
        .isInstanceOf(InstallationNotFoundException.class);
  }

  @Test
  @DisplayName("Config update passes the whole map through — a patch would not express a clear")
  void updateConfigReplacesWholesale() {
    when(installationService.updateConfig(INSTALLATION, ACTOR, Map.of("tone", "direct")))
        .thenReturn(installed("1.0.0", "1.0.0"));

    controller.updateConfig(
        INSTALLATION, new ConfigUpdateRequest(Map.of("tone", "direct")), ACTOR_JWT);

    verify(installationService).updateConfig(INSTALLATION, ACTOR, Map.of("tone", "direct"));
  }

  @Test
  @DisplayName("Upgrade moves the pin and is never implicit")
  void upgradeMovesThePin() {
    when(installationService.upgrade(INSTALLATION, ACTOR)).thenReturn(installed("1.1.0", "1.1.0"));

    assertThat(controller.upgrade(INSTALLATION, ACTOR_JWT).pinnedVersion()).isEqualTo("1.1.0");
  }

  @Test
  @DisplayName("Uninstall is 204 when it revoked, 404 when there was nothing of theirs to revoke")
  void uninstallReportsWhetherItActed() {
    when(installationService.uninstall(INSTALLATION, ACTOR)).thenReturn(true);
    assertThat(controller.uninstall(INSTALLATION, ACTOR_JWT).getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);

    when(installationService.uninstall(INSTALLATION, ACTOR)).thenReturn(false);
    assertThat(controller.uninstall(INSTALLATION, ACTOR_JWT).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }
}
