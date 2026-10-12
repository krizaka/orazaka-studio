package com.krizaka.orazaka.studio.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What a pack declares about a <b>conversational</b> turn taken under one installation (ADR-076).
 *
 * <p>The same four declarations a Studio step carries (ADR-051, ADR-055 §4, §7) — the domain the
 * pack refuses, what it treats as crisis content, the reviewed reply for the installation's region
 * — plus the one only a conversation needs: the pack's system prompt. A conversational turn has no
 * step and no dispatch, so nobody ferries these for it; the caller asks the studio service for
 * them, by installation, and puts them where the guards read them. The engine still never supplies
 * a subject of its own.
 *
 * <p>Read per turn, never cached, for {@code StepDeclarationService}'s reason: a pack is
 * re-installed to change what it refuses or what it answers, and a cache would keep enforcing the
 * previous boundary for as long as it lived.
 *
 * @param installationId the installation the turn is taken under — where consent and region live
 * @param studioKey the Studio installed
 * @param packKey the pack the Studio belongs to
 * @param region the region recorded with consent, which chooses the crisis line
 * @param scopeGuard the domain the pack refuses, or {@code null} when it declares none
 * @param crisisTerms what the pack treats as crisis content; empty when it declares none
 * @param crisisResponse the reviewed crisis text, without the line; {@code null} with no terms
 * @param crisisResource the region's verified line; {@code null} with no terms
 * @param systemPrompt the pack's own first system message, or {@code null}
 */
public record TurnDeclarations(
    UUID installationId,
    String studioKey,
    String packKey,
    String region,
    PackScopeGuard scopeGuard,
    List<String> crisisTerms,
    String crisisResponse,
    CrisisResource crisisResource,
    String systemPrompt) {

  /** Compact canonical constructor enforcing the declaration's invariants (ERR-106). */
  public TurnDeclarations {
    Objects.requireNonNull(installationId, "installationId must not be null");
    if (studioKey == null || studioKey.isBlank()) {
      throw new IllegalArgumentException("studioKey must not be blank");
    }
    crisisTerms = crisisTerms == null ? List.of() : List.copyOf(crisisTerms);
    // Half a crisis declaration is not one: terms with no reviewed reply would short-circuit a
    // person in crisis into silence, and a reply with no verified line would be a text that points
    // nowhere (ADR-055 §4, §5). Refused here so no caller can build either.
    boolean declared = !crisisTerms.isEmpty();
    if (declared
        && (crisisResponse == null || crisisResponse.isBlank() || crisisResource == null)) {
      throw new IllegalArgumentException(
          "crisis terms for "
              + studioKey
              + " need the reviewed reply and the verified line of region "
              + region);
    }
    if (!declared && (crisisResponse != null || crisisResource != null)) {
      throw new IllegalArgumentException("a crisis reply with no crisis terms guards nothing");
    }
    systemPrompt = systemPrompt == null || systemPrompt.isBlank() ? null : systemPrompt;
  }

  /**
   * Whether the pack declared a crisis guard for this turn.
   *
   * @return whether crisis terms, a reply and a line are all present
   */
  public boolean guardsCrisis() {
    return !crisisTerms.isEmpty();
  }

  /**
   * The whole reply the crisis guard answers: the reviewed text, then the region's verified line —
   * assembled once, here, exactly as {@link PackSafety#responseFor} assembles it for a Studio.
   *
   * @return the full reply, or {@code null} when the pack declares no crisis guard
   */
  public String crisisReply() {
    return guardsCrisis() ? crisisResponse + "\n\n" + crisisResource.render() : null;
  }
}
