package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.billing.domain.exception.InsufficientCreditsException;
import com.krizaka.billing.domain.model.BillableCapability;
import com.krizaka.orazaka.studio.domain.exception.BlueprintValidationException;
import com.krizaka.orazaka.studio.domain.exception.StudioNotEntitledException;
import com.krizaka.orazaka.studioservice.domain.exception.StudioIncludedException;
import com.krizaka.orazaka.studioservice.domain.exception.StudioNotFoundException;
import com.krizaka.orazaka.studioservice.domain.exception.StudioNotInstalledException;
import com.krizaka.orazaka.studioservice.domain.exception.TooManyConcurrentRunsException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class RestErrorResolverTest {

  private final RestErrorResolver resolver = new RestErrorResolver();

  @Test
  @DisplayName("The concurrency cap is 429 with Retry-After, not a bare 409 (ADR-083)")
  void concurrencyCapIs429WithRetryAfter() {
    ResponseEntity<Map<String, Object>> response =
        resolver.handleTooManyConcurrentRuns(new TooManyConcurrentRunsException(2, 2, 17));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("17");
    assertThat(response.getBody())
        .containsEntry("status", "too_many_concurrent_runs")
        .containsEntry("limit", 2)
        .containsEntry("active", 2)
        .containsEntry("retryAfterSeconds", 17);
  }

  @Test
  @DisplayName(
      "It is still an IllegalStateException, so a caller that catches the old refusal keeps"
          + " working")
  void concurrencyCapKeepsItsOldType() {
    assertThat(new TooManyConcurrentRunsException(2, 2, 10))
        .isInstanceOf(IllegalStateException.class);
  }

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
      "[ADR-061] an unentitled TOOLKIT and an uninstalled VERTICAL refuse in one shape, with the"
          + " pack")
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

  @Test
  @DisplayName(
      "[orazaka#301] the 451 carries the statement in the caller's language, every language and the regions' verified lines")
  void theConsentRefusalCarriesStatementsAndVerifiedLines() {
    var line =
        new com.krizaka.orazaka.studio.domain.model.CrisisResource(
            "CA-QC",
            "Ligne québécoise de prévention du suicide",
            "1 866 APPELLE (1 866 277-3553)",
            "En tout temps",
            "https://www.quebec.ca/",
            java.time.LocalDate.parse("2026-10-11"));

    ResponseEntity<Map<String, Object>> response =
        resolver.handleConsentRequired(
            new com.krizaka.orazaka.studioservice.domain.exception.ConsentRequiredException(
                "mood-tracking",
                "2.0",
                "This Studio records what you write.",
                Map.of("fr", "Ce Studio enregistre.", "en", "This Studio records what you write."),
                List.of(line)));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAVAILABLE_FOR_LEGAL_REASONS);
    assertThat(response.getBody())
        .containsEntry("status", "consent_required")
        .containsEntry("consentVersion", "2.0")
        .containsEntry("statement", "This Studio records what you write.")
        .containsEntry(
            "statements",
            Map.of("fr", "Ce Studio enregistre.", "en", "This Studio records what you write."));
    assertThat(response.getBody().get("regions"))
        .isEqualTo(
            List.of(
                new com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto
                    .CrisisLineResponse(
                    "CA-QC",
                    "Ligne québécoise de prévention du suicide",
                    "1 866 APPELLE (1 866 277-3553)",
                    "En tout temps",
                    "https://www.quebec.ca/",
                    "2026-10-11")));
  }

  @Test
  @DisplayName(
      "[orazaka#301] an erasure that could not be completed is 503 and says nothing was deleted")
  void anIncompleteErasureIs503() {
    ResponseEntity<Map<String, Object>> response =
        resolver.handleErasureIncomplete(
            new com.krizaka.orazaka.studioservice.domain.exception.ErasureIncompleteException(
                "the job plane is down", null));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody()).containsEntry("status", "erasure_incomplete");
  }

  @Test
  @DisplayName("[orazaka#301] a pack that holds no protected data has nothing to erase: 404")
  void anUnknownPackIs404() {
    ResponseEntity<Map<String, Object>> response =
        resolver.handlePackNotFound(
            new com.krizaka.orazaka.studioservice.domain.exception.PackNotFoundException("nope"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }
}
