package com.orazaka.studioservice.infrastructure.adapter.rest;

import com.orazaka.studioservice.application.service.BlueprintPublishService;
import com.orazaka.studioservice.application.service.StudioCatalogService;
import com.orazaka.studioservice.infrastructure.adapter.rest.dto.BlueprintDraftRequest;
import com.orazaka.studioservice.infrastructure.adapter.rest.dto.BlueprintVersionResponse;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The blueprints sub-resource: authoring a Studio's versions (ADR-034 §10).
 *
 * <p>Named after the resource, not the actor (ERR-128) — there is no {@code AdminController} here.
 * Admin access is a method concern, expressed with {@code @PreAuthorize} on the three mutating
 * methods, exactly as the naming rule requires.
 *
 * <p>This controller is the whole of "an admin ships a new Studio with zero code and zero deploy":
 * draft, publish, deprecate.
 */
@RestController
@RequestMapping("/api/v1/studios")
class StudioBlueprintController {

  private final BlueprintPublishService publishService;
  private final StudioCatalogService catalogService;

  StudioBlueprintController(
      BlueprintPublishService publishService, StudioCatalogService catalogService) {
    this.publishService =
        Objects.requireNonNull(publishService, "BlueprintPublishService required");
    this.catalogService = Objects.requireNonNull(catalogService, "StudioCatalogService required");
  }

  /**
   * Every version of a Studio, drafts included — the Builder's version list.
   *
   * @param studioKey the Studio
   * @return the versions, newest first
   */
  @GetMapping("/{studioKey}/blueprints")
  @PreAuthorize("hasRole('ADMIN')")
  List<BlueprintVersionResponse> list(@PathVariable String studioKey) {
    return catalogService.versions(studioKey).stream().map(BlueprintVersionResponse::from).toList();
  }

  /**
   * Creates or replaces a draft version.
   *
   * <p>Validation happens here, not at publish: the author finds out the graph is cyclic while they
   * are still looking at it.
   *
   * @param studioKey the Studio
   * @param version the semver to write
   * @param request the authored content
   * @param admin the authenticated admin
   * @return {@code 204} when the draft is stored and re-readable
   */
  @PutMapping("/{studioKey}/blueprints/{version}")
  @PreAuthorize("hasRole('ADMIN')")
  ResponseEntity<Void> saveDraft(
      @PathVariable String studioKey,
      @PathVariable String version,
      @RequestBody BlueprintDraftRequest request,
      @AuthenticationPrincipal Jwt admin) {
    publishService.saveDraft(
        studioKey,
        version,
        request.definition(),
        request.inputSchema(),
        request.configSchema(),
        request.estimatedCredits(),
        request.changelog(),
        admin.getSubject());
    return ResponseEntity.noContent().build();
  }

  /**
   * Publishes a draft, minting it as the Studio's latest version.
   *
   * @param studioKey the Studio
   * @param version the version to publish
   * @return {@code 204}; {@code 400} when it names a capability that is not enabled
   */
  @PostMapping("/{studioKey}/blueprints/{version}/publish")
  @PreAuthorize("hasRole('ADMIN')")
  ResponseEntity<Void> publish(@PathVariable String studioKey, @PathVariable String version) {
    publishService.publish(studioKey, version);
    return ResponseEntity.noContent().build();
  }

  /**
   * Deprecates a version without deleting it.
   *
   * @param studioKey the Studio
   * @param version the version to deprecate
   * @return {@code 204} when deprecated, {@code 404} when no such version
   */
  @DeleteMapping("/{studioKey}/blueprints/{version}")
  @PreAuthorize("hasRole('ADMIN')")
  ResponseEntity<Void> deprecate(@PathVariable String studioKey, @PathVariable String version) {
    return publishService.deprecate(studioKey, version)
        ? ResponseEntity.noContent().build()
        : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
  }
}
