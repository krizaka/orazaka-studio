package com.orazaka.studioservice.infrastructure.adapter.rest;

import com.orazaka.studio.domain.model.Studio;
import com.orazaka.studioservice.application.service.ComposerStudioService;
import com.orazaka.studioservice.application.service.StudioAccessService;
import com.orazaka.studioservice.application.service.StudioCatalogService;
import com.orazaka.studioservice.domain.exception.StudioNotFoundException;
import com.orazaka.studioservice.domain.model.BlueprintVersion;
import com.orazaka.studioservice.domain.model.StudioAccess;
import com.orazaka.studioservice.infrastructure.adapter.rest.dto.BlueprintVersionResponse;
import com.orazaka.studioservice.infrastructure.adapter.rest.dto.ComposerStudioResponse;
import com.orazaka.studioservice.infrastructure.adapter.rest.dto.StudioDetailResponse;
import com.orazaka.studioservice.infrastructure.adapter.rest.dto.StudioSummaryResponse;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The studios resource — the marketplace catalogue (ADR-034 §10).
 *
 * <p>Named after the noun it manages, not the actor reading it (ERR-128). Locked Studios are
 * returned rather than filtered: the catalogue's job is to sell, and an actor who cannot see a
 * product cannot want it.
 */
@RestController
@RequestMapping("/api/v1/studios")
class StudioController {

  private static final String DEFAULT_LOCALE = "fr";

  private final StudioCatalogService catalogService;
  private final StudioAccessService accessService;
  private final ComposerStudioService composerStudioService;

  StudioController(
      StudioCatalogService catalogService,
      StudioAccessService accessService,
      ComposerStudioService composerStudioService) {
    this.catalogService = Objects.requireNonNull(catalogService, "StudioCatalogService required");
    this.accessService = Objects.requireNonNull(accessService, "StudioAccessService required");
    this.composerStudioService =
        Objects.requireNonNull(composerStudioService, "ComposerStudioService required");
  }

  /**
   * The Studios that belong in a chat composer, for this actor.
   *
   * <p>A genuine sub-resource and not a client-shaped endpoint: it answers a question about the
   * catalogue — which Studios are launchable from a single input — that any surface with a
   * one-field affordance asks. The predicate is structural (one step, one required input), so a
   * Studio joins or leaves this row by changing its blueprint, never by editing a list here.
   *
   * @param locale the caller's locale; the label and icon come from the pack's own i18n
   * @param actor the authenticated caller, whose entitlements decide each button's locked state
   * @return the buttons, in catalogue order, locked ones included
   */
  @GetMapping("/composer")
  List<ComposerStudioResponse> composer(
      @RequestParam(defaultValue = DEFAULT_LOCALE) String locale,
      @AuthenticationPrincipal Jwt actor) {
    return composerStudioService.row(locale, actor.getSubject()).stream()
        .map(ComposerStudioResponse::from)
        .toList();
  }

  /**
   * The catalogue, optionally narrowed to one profession.
   *
   * @param profession the métier to filter on, or absent for everything
   * @param locale the caller's locale; rows without a translation fall back to the base row
   * @param actor the authenticated caller, whose entitlements decide each card's locked state
   * @return the cards, heaviest sort weight first
   */
  @GetMapping
  List<StudioSummaryResponse> browse(
      @RequestParam(required = false) String profession,
      @RequestParam(defaultValue = DEFAULT_LOCALE) String locale,
      @AuthenticationPrincipal Jwt actor) {
    List<Studio> studios = catalogService.browse(profession, locale);
    Map<String, StudioAccess> access = accessService.evaluateAll(studios, actor.getSubject());
    return studios.stream()
        .map(studio -> StudioSummaryResponse.from(studio, access.get(studio.studioKey())))
        .toList();
  }

  /**
   * One Studio's detail, including the schemas its forms are generated from.
   *
   * @param studioKey the Studio's key
   * @param locale the caller's locale
   * @param actor the authenticated caller
   * @return the detail payload
   * @throws StudioNotFoundException when the key names nothing — distinct from locked, which is a
   *     Studio that exists and is worth buying
   */
  @GetMapping("/{studioKey}")
  StudioDetailResponse find(
      @PathVariable String studioKey,
      @RequestParam(defaultValue = DEFAULT_LOCALE) String locale,
      @AuthenticationPrincipal Jwt actor) {
    Studio studio =
        catalogService
            .find(studioKey, locale)
            .orElseThrow(() -> new StudioNotFoundException(studioKey));

    Optional<BlueprintVersion> latest = catalogService.latestPublished(studioKey);
    String version = latest.map(BlueprintVersion::version).orElse(null);
    return StudioDetailResponse.from(
        studio,
        catalogService.description(studioKey, locale).orElse(null),
        latest.map(BlueprintVersion::estimatedCredits).orElse(0L),
        version == null ? null : catalogService.inputSchema(studioKey, version).orElse(null),
        version == null ? null : catalogService.configSchema(studioKey, version).orElse(null),
        accessService.evaluate(studio, actor.getSubject()));
  }

  /**
   * A Studio's version history.
   *
   * @param studioKey the Studio's key
   * @return every version with its changelog, newest first
   * @throws StudioNotFoundException when the key names nothing
   */
  @GetMapping("/{studioKey}/versions")
  List<BlueprintVersionResponse> versions(@PathVariable String studioKey) {
    if (catalogService.find(studioKey, DEFAULT_LOCALE).isEmpty()) {
      throw new StudioNotFoundException(studioKey);
    }
    return catalogService.versions(studioKey).stream().map(BlueprintVersionResponse::from).toList();
  }
}
