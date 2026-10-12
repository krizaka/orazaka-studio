package com.krizaka.orazaka.studio.domain.port;

import com.krizaka.orazaka.studio.domain.model.TurnDeclarations;
import java.util.Optional;
import java.util.UUID;

/**
 * What a conversational turn must carry when it is taken under an installation (ADR-076).
 *
 * <p>The studio service owns the pack rows and the installations; a conversation service owns the
 * turn. This is the one question the second asks the first, so the guards that run inside the turn
 * run on the pack's own declarations and never on a copy.
 */
public interface TurnDeclarationClient {

  /**
   * The declarations of one installation, scoped to its owner.
   *
   * @param installationId the installation
   * @param actorId the person taking the turn
   * @return the declarations, or empty when the id names nothing this actor owns or the
   *     installation is not active — the same answer for both, on purpose
   * @throws com.krizaka.orazaka.studio.domain.exception.TurnConsentRequiredException when the
   *     recorded consent is not to the statement the pack now requires
   * @throws IllegalStateException when the studio service cannot be asked — a turn that cannot read
   *     its pack's guards is not taken (fail closed)
   */
  Optional<TurnDeclarations> forInstallation(UUID installationId, String actorId);
}
