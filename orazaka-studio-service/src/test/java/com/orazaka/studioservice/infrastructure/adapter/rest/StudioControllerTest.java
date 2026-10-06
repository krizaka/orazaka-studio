package com.orazaka.studioservice.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.orazaka.studio.domain.model.BlueprintStatus;
import com.orazaka.studio.domain.model.PackKind;
import com.orazaka.studio.domain.model.Studio;
import com.orazaka.studio.domain.model.StudioPricing;
import com.orazaka.studio.domain.model.StudioStatus;
import com.orazaka.studioservice.application.service.ComposerStudioService;
import com.orazaka.studioservice.application.service.StudioAccessService;
import com.orazaka.studioservice.application.service.StudioCatalogService;
import com.orazaka.studioservice.domain.exception.StudioNotFoundException;
import com.orazaka.studioservice.domain.model.BlueprintVersion;
import com.orazaka.studioservice.domain.model.LockReason;
import com.orazaka.studioservice.domain.model.StudioAccess;
import com.orazaka.studioservice.infrastructure.adapter.rest.dto.StudioDetailResponse;
import com.orazaka.studioservice.infrastructure.adapter.rest.dto.StudioSummaryResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class StudioControllerTest {

  private static final String ACTOR = "550e8400-e29b-41d4-a716-446655440002";

  private final StudioCatalogService catalogService = mock(StudioCatalogService.class);
  private final StudioAccessService accessService = mock(StudioAccessService.class);
  private final ComposerStudioService composerStudioService = mock(ComposerStudioService.class);
  private final StudioController controller =
      new StudioController(catalogService, accessService, composerStudioService);

  private static final Jwt ACTOR_JWT =
      Jwt.withTokenValue("t").header("alg", "HS256").subject(ACTOR).build();

  private static Studio studio(String key, StudioPricing pricing, String packKey) {
    return new Studio(
        key,
        "Vitrine Artisan",
        "Vos chantiers deviennent une vitrine.",
        "trades",
        "studio",
        null,
        pricing,
        packKey,
        PackKind.VERTICAL,
        "studio." + key,
        StudioStatus.PUBLISHED,
        "orazaka",
        "1.0.0",
        Set.of("fr"),
        Instant.parse("2026-08-05T10:00:00Z"));
  }

  @Test
  @DisplayName("The catalogue returns locked studios rather than hiding them — the funnel needs it")
  void catalogueReturnsLockedCards() {
    Studio locked = studio("realestate-reels", StudioPricing.PAID, "realestate-studio");
    when(catalogService.browse(null, "fr")).thenReturn(List.of(locked));
    when(accessService.evaluateAll(any(), anyString()))
        .thenReturn(
            Map.of(
                "realestate-reels",
                new StudioAccess(true, LockReason.REQUIRES_PURCHASE, "realestate-studio")));

    List<StudioSummaryResponse> cards = controller.browse(null, "fr", ACTOR_JWT);

    assertThat(cards).hasSize(1);
    assertThat(cards.get(0).locked()).isTrue();
    assertThat(cards.get(0).packKey()).isEqualTo("realestate-studio");
  }

  @Test
  @DisplayName("The requested locale is passed straight through to the catalogue")
  void localeIsPassedThrough() {
    when(catalogService.browse("trades", "en")).thenReturn(List.of());
    when(accessService.evaluateAll(any(), anyString())).thenReturn(Map.of());

    assertThat(controller.browse("trades", "en", ACTOR_JWT)).isEmpty();
  }

  @Test
  @DisplayName("Detail carries the pinned version's schemas so the client generates its own forms")
  void detailCarriesSchemas() {
    Studio studio = studio("trade-showcase", StudioPricing.FREE, null);
    when(catalogService.find("trade-showcase", "fr")).thenReturn(Optional.of(studio));
    when(catalogService.description("trade-showcase", "fr"))
        .thenReturn(Optional.of("Longue copie"));
    when(catalogService.latestPublished("trade-showcase"))
        .thenReturn(
            Optional.of(
                new BlueprintVersion(
                    "trade-showcase", "1.0.0", BlueprintStatus.PUBLISHED, 80, "initial", null)));
    when(catalogService.inputSchema("trade-showcase", "1.0.0"))
        .thenReturn(Optional.of("{\"a\":1}"));
    when(catalogService.configSchema("trade-showcase", "1.0.0"))
        .thenReturn(Optional.of("{\"b\":2}"));
    when(accessService.evaluate(any(), anyString())).thenReturn(StudioAccess.open());

    StudioDetailResponse detail = controller.find("trade-showcase", "fr", ACTOR_JWT);

    assertThat(detail.estimatedCredits()).isEqualTo(80);
    assertThat(detail.inputSchema()).isEqualTo("{\"a\":1}");
    assertThat(detail.configSchema()).isEqualTo("{\"b\":2}");
    assertThat(detail.description()).isEqualTo("Longue copie");
  }

  @Test
  @DisplayName("An unpublished studio has no schemas and costs nothing — not an error")
  void unpublishedStudioHasNoSchemas() {
    when(catalogService.find("draft-one", "fr"))
        .thenReturn(Optional.of(studio("draft-one", StudioPricing.FREE, null)));
    when(catalogService.description("draft-one", "fr")).thenReturn(Optional.empty());
    when(catalogService.latestPublished("draft-one")).thenReturn(Optional.empty());
    when(accessService.evaluate(any(), anyString())).thenReturn(StudioAccess.open());

    StudioDetailResponse detail = controller.find("draft-one", "fr", ACTOR_JWT);

    assertThat(detail.inputSchema()).isNull();
    assertThat(detail.estimatedCredits()).isZero();
  }

  @Test
  @DisplayName("An unknown key is 404, never a locked 409 — it is not a product to sell")
  void unknownKeyIsNotFound() {
    when(catalogService.find("nope", "fr")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.find("nope", null, ACTOR_JWT))
        .isInstanceOf(StudioNotFoundException.class);
  }

  @Test
  @DisplayName("Version history is refused for an unknown studio rather than returning empty")
  void versionsRejectUnknownStudio() {
    when(catalogService.find("nope", "fr")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.versions("nope"))
        .isInstanceOf(StudioNotFoundException.class);
  }

  @Test
  @DisplayName("Version history projects every version with its changelog")
  void versionsProjectHistory() {
    when(catalogService.find("trade-showcase", "fr"))
        .thenReturn(Optional.of(studio("trade-showcase", StudioPricing.FREE, null)));
    when(catalogService.versions("trade-showcase"))
        .thenReturn(
            List.of(
                new BlueprintVersion(
                    "trade-showcase",
                    "1.0.0",
                    BlueprintStatus.PUBLISHED,
                    80,
                    "Vitrine initiale",
                    Instant.parse("2026-08-05T10:00:00Z"))));

    assertThat(controller.versions("trade-showcase"))
        .singleElement()
        .satisfies(
            version -> {
              assertThat(version.version()).isEqualTo("1.0.0");
              assertThat(version.changelog()).isEqualTo("Vitrine initiale");
            });
  }
}
