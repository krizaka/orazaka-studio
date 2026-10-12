package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.studio.domain.model.TurnDeclarations;
import com.krizaka.orazaka.studioservice.application.service.StepDeclarationService;
import com.krizaka.orazaka.studioservice.domain.exception.InstallationNotFoundException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * An installation's declarations, as a conversational turn needs them (ADR-076).
 *
 * <p>Service-to-service only: {@code /internal/v1/**} is reserved for the {@code SERVICE} authority
 * by the Krizaka security baseline (ADR-035), so a browser never reads a pack's crisis terms. The
 * calling service names the actor it acts for, and the row predicate scopes the lookup to that
 * actor exactly as the public installation endpoints do.
 */
@RestController
@RequestMapping("/internal/v1/installations")
class InstallationDeclarationController {

  private final StepDeclarationService declarations;

  InstallationDeclarationController(StepDeclarationService declarations) {
    this.declarations = Objects.requireNonNull(declarations, "StepDeclarationService required");
  }

  /**
   * What a turn taken under this installation must carry: the pack's guards, the reviewed crisis
   * reply for the installation's region, and the pack's system prompt.
   *
   * @param installationId the installation
   * @param actorId the person the calling service acts for
   * @return the declarations; {@code 404} when the id names no active installation of this actor;
   *     {@code 451} with the statement when consent is not to the version in force
   */
  @GetMapping("/{installationId}/turn-declarations")
  TurnDeclarations turnDeclarations(
      @PathVariable UUID installationId, @RequestParam String actorId) {
    return declarations
        .forTurn(installationId, actorId)
        .orElseThrow(() -> new InstallationNotFoundException(installationId));
  }
}
