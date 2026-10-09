package com.orazaka.studioservice.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.billing.domain.exception.InsufficientCreditsException;
import com.krizaka.billing.domain.model.BillableCapability;
import com.orazaka.studio.domain.exception.BlueprintValidationException;
import com.orazaka.studio.domain.exception.StudioNotEntitledException;
import com.orazaka.studioservice.domain.exception.StudioIncludedException;
import com.orazaka.studioservice.domain.exception.StudioNotFoundException;
import com.orazaka.studioservice.domain.exception.StudioNotInstalledException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class RestErrorResolverTest {

  private final RestErrorResolver resolver = new RestErrorResolver();

  @Test
  @DisplayName("An unknown studio is 404")
  void unknownStudioIs404() {
    ResponseEntity<Map<String, Object>> response =
        resolver.handleNotFound(new StudioNotFoundException("nope"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).containsEntry("status", "studio_not_found");
  }

  @Test
  @DisplayName("An unentitled PAID studio is 409 with the pack, so the client opens checkout")
  void unentitledPaidIs409WithPackage() {
    ResponseEntity<Map<String, Object>> response =
        resolver.handleNotEntitled(
            new StudioNotEntitledException(
                "realestate-reels", "studio.realestate-reels", "realestate-studio"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody())
        .containsEntry("packKey", "realestate-studio")
        .containsEntry("remedies", List.of("buy_package"));
  }

  @Test
  @DisplayName("A plan gap offers an upgrade, not a checkout that does not exist")
  void planGapOffersUpgrade() {
    ResponseEntity<Map<String, Object>> response =
        resolver.handleNotEntitled(
            new StudioNotEntitledException("trade-showcase", "studio.tier.premium", null));

    assertThat(response.getBody())
        .containsEntry("packKey", null)
        .containsEntry("remedies", List.of("upgrade_plan"));
  }

  @Test
  @DisplayName("An unaffordable run is 402 — owned but broke is not the same as not owned")
  void unaffordableRunIs402() {
    ResponseEntity<Map<String, Object>> response =
        resolver.handleInsufficientCredits(
            new InsufficientCreditsException(
                "actor-1", BillableCapability.IMAGE, 80, 10, List.of("topup")));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
    assertThat(response.getBody())
        .containsEntry("required", 80L)
        .containsEntry("balance", 10L)
        .containsEntry("remedies", List.of("topup"));
  }

  @Test
  @DisplayName("An invalid blueprint is 400 naming the offending step")
  void invalidBlueprintNamesTheStep() {
    ResponseEntity<Map<String, Object>> response =
        resolver.handleInvalidBlueprint(
            new BlueprintValidationException("describe", "dependsOn names an unknown step"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsEntry("stepId", "describe");
  }

  @Test
  @DisplayName("A graph-wide violation reports no step id rather than blaming an arbitrary node")
  void graphWideViolationHasNoStepId() {
    ResponseEntity<Map<String, Object>> response =
        resolver.handleInvalidBlueprint(new BlueprintValidationException("the graph is cyclic"));

    assertThat(response.getBody()).containsEntry("stepId", null);
  }

  // ── ADR-061: one refusal shape ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "[ADR-061] an unentitled TOOLKIT and an uninstalled VERTICAL refuse in one shape, with the pack")
  void unentitledAndUninstalledShareOneShape() {
    ResponseEntity<Map<String, Object>> unentitled =
        resolver.handleNotEntitled(
            new StudioNotEntitledException("image-generation", "studio.image-generation", "media"));
    ResponseEntity<Map<String, Object>> uninstalled =
        resolver.handleNotInstalled(
            new StudioNotInstalledException(
                "realestate-reels", "studio.realestate-reels", "realestate-studio"));

    // The contract a single client branch depends on: same status, same keys, the pack to open.
    assertThat(uninstalled.getStatusCode()).isEqualTo(unentitled.getStatusCode());
    assertThat(uninstalled.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(uninstalled.getBody()).containsOnlyKeys(unentitled.getBody().keySet());
    assertThat(unentitled.getBody()).containsEntry("packKey", "media");
    assertThat(uninstalled.getBody())
        .containsEntry("packKey", "realestate-studio")
        .containsEntry("status", "studio_not_installed")
        .containsEntry("remedies", List.of("install"));
  }

  @Test
  @DisplayName(
      "[ADR-061] installing a TOOLKIT is a 409 in the same shape, whose remedy is to run it")
  void installingAToolkitIsAConflictInTheSameShape() {
    ResponseEntity<Map<String, Object>> included =
        resolver.handleIncluded(
            new StudioIncludedException("image-generation", "studio.image-generation", "media"));
    ResponseEntity<Map<String, Object>> unentitled =
        resolver.handleNotEntitled(
            new StudioNotEntitledException("image-generation", "studio.image-generation", "media"));

    assertThat(included.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(included.getBody())
        .containsOnlyKeys(unentitled.getBody().keySet())
        .containsEntry("status", "studio_included")
        .containsEntry("remedies", List.of("run"));
  }

  @Test
  @DisplayName("[ADR-061] a refusal with no pack still carries the key, so the shape never varies")
  void aNullPackIsAPresentKey() {
    ResponseEntity<Map<String, Object>> response =
        resolver.handleNotInstalled(
            new StudioNotInstalledException("trade-showcase", "studio.trade-showcase", null));

    assertThat(response.getBody()).containsKey("packKey");
    assertThat(response.getBody().get("packKey")).isNull();
  }
}
