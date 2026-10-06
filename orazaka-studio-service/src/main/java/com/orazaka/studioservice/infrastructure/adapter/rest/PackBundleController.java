package com.orazaka.studioservice.infrastructure.adapter.rest;

import com.orazaka.studio.domain.model.PackBundle;
import com.orazaka.studioservice.application.service.PackInstallerService;
import com.orazaka.studioservice.infrastructure.adapter.rest.dto.PackInstallResponse;
import com.orazaka.studioservice.infrastructure.adapter.rest.dto.PackValidationResponse;
import java.util.List;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The install surface for pack bundles (ADR-037, seam S4).
 *
 * <p>A genuine sub-resource of the pack, distinct from {@link PackCatalogController}: that one
 * serves the marketplace — anonymous browsing of what is published — and this one applies a
 * manifest. Splitting them keeps the browse path free of the authorisation the install path
 * demands, which is [ERR-128]'s reason for resource-oriented controllers rather than actor-oriented
 * ones: access is a method concern, and these two methods do not share one.
 *
 * <p>{@code hasRole('ADMIN')}, not the {@code SERVICE} authority. Installing a pack adds Studios to
 * a catalogue, opens a shelf and creates a sellable price — a human's decision, taken by a human
 * who is accountable for it. The service-to-service calls this install makes downstream are what
 * carry {@code SERVICE}; the decision to install does not.
 */
@RestController
@RequestMapping("/api/v1/studios/packs/bundles")
class PackBundleController {

  private final PackInstallerService packInstallerService;

  PackBundleController(PackInstallerService packInstallerService) {
    this.packInstallerService =
        Objects.requireNonNull(packInstallerService, "PackInstallerService cannot be null");
  }

  /**
   * Checks a bundle against this platform without writing anything.
   *
   * <p>Answers the half of validation a file cannot: whether every capability the bundle's
   * blueprints name actually resolves here. The manifest's shape is already checked against {@code
   * pack.schema.json} before it is sent.
   *
   * @param bundle the resolved manifest
   * @return the problems found, empty when the bundle is installable
   */
  @PostMapping("/validation")
  @PreAuthorize("hasRole('ADMIN')")
  PackValidationResponse validate(@RequestBody PackBundle bundle) {
    List<String> problems = packInstallerService.validate(bundle);
    return new PackValidationResponse(bundle.key(), bundle.version(), problems.isEmpty(), problems);
  }

  /**
   * Installs a bundle.
   *
   * @param bundle the resolved manifest
   * @return what was installed
   */
  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  PackInstallResponse install(@RequestBody PackBundle bundle) {
    int studios = packInstallerService.install(bundle);
    return new PackInstallResponse(bundle.key(), bundle.version(), studios);
  }

  /**
   * Removes a bundle's catalogue rows and withdraws its pack from sale.
   *
   * @param bundleKey the bundle to remove
   * @return {@code 204} when removed, {@code 404} when nothing carried that key
   */
  @DeleteMapping("/{bundleKey}")
  @PreAuthorize("hasRole('ADMIN')")
  ResponseEntity<Void> uninstall(@PathVariable String bundleKey) {
    return packInstallerService.uninstall(bundleKey)
        ? ResponseEntity.noContent().build()
        : ResponseEntity.notFound().build();
  }
}
