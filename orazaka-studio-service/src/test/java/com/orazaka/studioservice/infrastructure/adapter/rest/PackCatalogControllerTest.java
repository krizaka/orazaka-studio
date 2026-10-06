package com.orazaka.studioservice.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.orazaka.studio.domain.model.Pack;
import com.orazaka.studio.domain.model.PackCategory;
import com.orazaka.studio.domain.model.PackKind;
import com.orazaka.studio.domain.model.PackStatus;
import com.orazaka.studio.domain.model.PackSummary;
import com.orazaka.studio.domain.model.RegulatoryClass;
import com.orazaka.studioservice.application.service.PackCatalogService;
import com.orazaka.studioservice.infrastructure.adapter.rest.dto.PackDetailResponse;
import com.orazaka.studioservice.infrastructure.adapter.rest.dto.PackSummaryResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class PackCatalogControllerTest {

  private static final PackCategory BUSINESS =
      new PackCategory("business", "Business", null, "briefcase", 100, true);

  /** The authenticated caller; the band is a decision about this subject, not about the pack. */
  private static final org.springframework.security.oauth2.jwt.Jwt ACTOR =
      org.springframework.security.oauth2.jwt.Jwt.withTokenValue("t")
          .header("alg", "none")
          .subject("550e8400-e29b-41d4-a716-446655440066")
          .build();

  private final PackCatalogService catalogService = mock(PackCatalogService.class);
  private final PackCatalogController controller = new PackCatalogController(catalogService);

  @org.junit.jupiter.api.BeforeEach
  void bands() {
    // Whatever the page holds, every card gets a band: the mapping asks for one per key.
    org.mockito.Mockito.lenient()
        .when(
            catalogService.bands(
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(
            call -> {
              java.util.List<PackSummary> cards = call.getArgument(0);
              java.util.Map<String, com.orazaka.studioservice.domain.model.PackAccess> bands =
                  new java.util.LinkedHashMap<>();
              cards.forEach(
                  card ->
                      bands.put(
                          card.pack().packKey(),
                          com.orazaka.studioservice.domain.model.PackAccess.BUYABLE));
              return bands;
            });
  }

  private static PackSummary card(Integer priceCents) {
    Pack pack =
        new Pack(
            "realestate-studio",
            "business",
            "Studio Immobilier",
            "Vos biens deviennent des Reels.",
            null,
            "studio",
            null,
            RegulatoryClass.STANDARD,
            PackKind.VERTICAL,
            PackStatus.PUBLISHED,
            100,
            List.of("realestate-reels"),
            Set.of("fr"),
            Instant.parse("2026-08-27T10:00:00Z"));
    return new PackSummary(pack, BUSINESS, priceCents, priceCents == null ? null : 5000L);
  }

  @Test
  @DisplayName("Browsing defaults to French, because the product's first market is French")
  void browseDefaultsToFrench() {
    when(catalogService.browse(isNull(), org.mockito.ArgumentMatchers.eq("fr")))
        .thenReturn(List.of(card(4900)));

    List<PackSummaryResponse> cards = controller.browse(null, "fr", ACTOR);

    assertThat(cards).hasSize(1);
    assertThat(cards.get(0).categoryKey()).isEqualTo("business");
    assertThat(cards.get(0).priceCents()).isEqualTo(4900);
  }

  @Test
  @DisplayName("A card with no price is still returned — degraded, not dropped")
  void unpricedCardsStillRender() {
    // The alternative — hiding a card because billing did not answer — makes a marketplace
    // silently shrink during an outage, which is worse than a card reading "—".
    when(catalogService.browse(isNull(), org.mockito.ArgumentMatchers.eq("fr")))
        .thenReturn(List.of(card(null)));

    assertThat(controller.browse(null, "fr", ACTOR).get(0).priceCents()).isNull();
  }

  @Test
  @DisplayName("An unknown pack is 404, which is distinct from a pack with no price")
  void unknownPackIs404() {
    when(catalogService.summary("no-such-pack", "fr")).thenReturn(Optional.empty());

    ResponseEntity<PackDetailResponse> response = controller.find("no-such-pack", "fr", ACTOR);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("A known pack returns its detail with its shelf resolved")
  void knownPackReturnsDetail() {
    when(catalogService.summary("realestate-studio", "fr")).thenReturn(Optional.of(card(4900)));

    ResponseEntity<PackDetailResponse> response = controller.find("realestate-studio", "fr", ACTOR);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().category().label()).isEqualTo("Business");
  }

  @Test
  @DisplayName("The shelves are served, so the client renders headings it did not invent")
  void servesShelves() {
    when(catalogService.categories("fr")).thenReturn(List.of(BUSINESS));

    assertThat(controller.categories("fr"))
        .singleElement()
        .satisfies(
            shelf -> {
              assertThat(shelf.categoryKey()).isEqualTo("business");
              assertThat(shelf.label()).isEqualTo("Business");
            });
  }
}
