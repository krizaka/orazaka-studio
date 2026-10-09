package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.studio.domain.model.PackSummary;
import com.krizaka.orazaka.studioservice.application.service.PackCatalogService;
import com.krizaka.orazaka.studioservice.domain.model.PackAccess;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto.PackCategoryResponse;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto.PackDetailResponse;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto.PackSummaryResponse;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The packs resource — the categorised marketplace (ADR-036).
 *
 * <p>Named after the noun it manages, not the actor browsing it (ERR-128). It sits under {@code
 * /api/v1/studios} because a Pack is a bundle of Studios and the two are one catalogue: the same
 * i18n mechanism, the same admin console, the same marketplace query. The literal {@code /packs}
 * segment is matched ahead of {@link StudioController}'s {@code /{studioKey}} — Spring prefers the
 * literal pattern — so the two resources coexist under one prefix without an ordering rule anyone
 * has to remember.
 *
 * <p>Nothing here is entitlement-filtered, for the reason the Studio catalogue is not either: a
 * Pack the actor has not bought is the top of the funnel, and an actor who cannot see a product
 * cannot want it. What they may <em>run</em> is decided at install and run time.
 *
 * <p>The price comes from billing across a context boundary and may be absent. That is a degraded
 * card, not an error — see {@link PackSummaryResponse}.
 */
@RestController
@RequestMapping("/api/v1/studios/packs")
class PackCatalogController {

  private static final String DEFAULT_LOCALE = "fr";

  private final PackCatalogService packCatalogService;

  PackCatalogController(PackCatalogService packCatalogService) {
    this.packCatalogService =
        Objects.requireNonNull(packCatalogService, "PackCatalogService required");
  }

  /**
   * The marketplace, optionally narrowed to one shelf.
   *
   * @param category the shelf to browse, or absent for every shelf
   * @param locale the caller's locale; a Pack without that translation falls back
   * @param actor the authenticated caller, whose entitlements decide each card's band
   * @return the cards, heaviest sort weight first
   */
  @GetMapping
  List<PackSummaryResponse> browse(
      @RequestParam(required = false) String category,
      @RequestParam(defaultValue = DEFAULT_LOCALE) String locale,
      @AuthenticationPrincipal Jwt actor) {
    List<PackSummary> cards = packCatalogService.browse(category, locale);
    // One entitlement snapshot for the page, and the band decided here rather than by the client:
    // `kind` alone said "included" to an actor entitled to nothing (ADR-066).
    Map<String, PackAccess> bands = packCatalogService.bands(cards, actor.getSubject());
    return cards.stream()
        .map(card -> PackSummaryResponse.from(card, bands.get(card.pack().packKey())))
        .toList();
  }

  /**
   * The shelves themselves, so the client renders headings it did not have to invent.
   *
   * @param locale the caller's locale
   * @return the active categories, heaviest sort weight first
   */
  @GetMapping("/categories")
  List<PackCategoryResponse> categories(
      @RequestParam(defaultValue = DEFAULT_LOCALE) String locale) {
    return packCatalogService.categories(locale).stream().map(PackCategoryResponse::from).toList();
  }

  /**
   * One Pack's detail.
   *
   * @param packKey the Pack's key
   * @param locale the caller's locale
   * @param actor the authenticated caller, whose entitlements decide the band
   * @return {@code 200} with the detail, or {@code 404} when the key names nothing
   */
  @GetMapping("/{packKey}")
  ResponseEntity<PackDetailResponse> find(
      @PathVariable String packKey,
      @RequestParam(defaultValue = DEFAULT_LOCALE) String locale,
      @AuthenticationPrincipal Jwt actor) {
    return packCatalogService
        .summary(packKey, locale)
        .map(
            card ->
                PackDetailResponse.from(
                    card, packCatalogService.bands(List.of(card), actor.getSubject()).get(packKey)))
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
