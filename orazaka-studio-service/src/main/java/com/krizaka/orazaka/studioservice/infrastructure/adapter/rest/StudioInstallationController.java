package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.studioservice.application.service.StudioInstallationService;
import com.krizaka.orazaka.studioservice.domain.exception.InstallationNotFoundException;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto.ConfigUpdateRequest;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto.InstallRequest;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto.InstallationResponse;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The installations sub-resource: one actor's copies of Studios (ADR-034 §10).
 *
 * <p>A genuine sub-resource of {@code /api/v1/studios}, which is why it is its own controller
 * rather than more methods on {@link StudioController} (ERR-128). No {@code @PreAuthorize} appears
 * here: the authorisation that matters is not a role but ownership, and that is enforced as a row
 * predicate in the service — every statement is scoped by the caller's actor id.
 */
@RestController
@RequestMapping("/api/v1/studios")
class StudioInstallationController {

  private static final String DEFAULT_LOCALE = "fr";

  private final StudioInstallationService installationService;

  StudioInstallationController(StudioInstallationService installationService) {
    this.installationService =
        Objects.requireNonNull(installationService, "StudioInstallationService required");
  }

  /**
   * Installs a Studio, pinning its newest published version.
   *
   * @param studioKey the Studio to install
   * @param request the actor's answers to the config schema
   * @param locale the caller's locale, for the label carried back
   * @param actor the authenticated caller
   * @return {@code 201} with the installation; {@code 409} with the pack when unentitled
   */
  @PostMapping("/{studioKey}/installations")
  ResponseEntity<InstallationResponse> install(
      @PathVariable String studioKey,
      @RequestBody(required = false) InstallRequest request,
      @RequestParam(defaultValue = DEFAULT_LOCALE) String locale,
      @AuthenticationPrincipal Jwt actor) {
    InstallRequest resolved = request == null ? new InstallRequest(null) : request;
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            InstallationResponse.from(
                installationService.install(
                    studioKey, actor.getSubject(), resolved.config(), locale, resolved.consent())));
  }

  /**
   * "My Studios".
   *
   * @param actor the authenticated caller
   * @return their installations, most recently installed first
   */
  @GetMapping("/installations")
  List<InstallationResponse> list(@AuthenticationPrincipal Jwt actor) {
    return installationService.listFor(actor.getSubject()).stream()
        .map(InstallationResponse::from)
        .toList();
  }

  /**
   * One installation.
   *
   * @param installationId the installation
   * @param actor the authenticated caller
   * @return the installation
   * @throws InstallationNotFoundException when the id names nothing this actor owns — the same
   *     answer as an id that belongs to somebody else, on purpose
   */
  @GetMapping("/installations/{installationId}")
  InstallationResponse find(@PathVariable UUID installationId, @AuthenticationPrincipal Jwt actor) {
    return installationService
        .find(installationId, actor.getSubject())
        .map(InstallationResponse::from)
        .orElseThrow(() -> new InstallationNotFoundException(installationId));
  }

  /**
   * Replaces an installation's configuration.
   *
   * @param installationId the installation
   * @param request the new answers
   * @param actor the authenticated caller
   * @return the installation as stored
   */
  @PatchMapping("/installations/{installationId}")
  InstallationResponse updateConfig(
      @PathVariable UUID installationId,
      @RequestBody ConfigUpdateRequest request,
      @AuthenticationPrincipal Jwt actor) {
    return InstallationResponse.from(
        installationService.updateConfig(installationId, actor.getSubject(), request.config()));
  }

  /**
   * Moves the pin to the newest published version — never automatic.
   *
   * @param installationId the installation
   * @param actor the authenticated caller
   * @return the installation as stored
   */
  @PostMapping("/installations/{installationId}/upgrade")
  InstallationResponse upgrade(
      @PathVariable UUID installationId, @AuthenticationPrincipal Jwt actor) {
    return InstallationResponse.from(
        installationService.upgrade(installationId, actor.getSubject()));
  }

  /**
   * Uninstalls, keeping the configuration for a future reinstall.
   *
   * @param installationId the installation
   * @param actor the authenticated caller
   * @return {@code 204} when revoked, {@code 404} when the id names nothing this actor owns
   */
  @DeleteMapping("/installations/{installationId}")
  ResponseEntity<Void> uninstall(
      @PathVariable UUID installationId, @AuthenticationPrincipal Jwt actor) {
    return installationService.uninstall(installationId, actor.getSubject())
        ? ResponseEntity.noContent().build()
        : ResponseEntity.notFound().build();
  }
}
