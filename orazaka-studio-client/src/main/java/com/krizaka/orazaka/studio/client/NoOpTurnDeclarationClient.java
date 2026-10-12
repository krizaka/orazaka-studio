package com.krizaka.orazaka.studio.client;

import com.krizaka.orazaka.studio.domain.model.TurnDeclarations;
import com.krizaka.orazaka.studio.domain.port.TurnDeclarationClient;
import java.util.Optional;
import java.util.UUID;

/**
 * Null Object: no marketplace, so no installation to take a turn under (ADR-076).
 *
 * <p>Refuses rather than answering "no declarations": a turn that names an installation is asking
 * for its pack's guards, and an empty answer would let it run unguarded.
 */
final class NoOpTurnDeclarationClient implements TurnDeclarationClient {

  @Override
  public Optional<TurnDeclarations> forInstallation(UUID installationId, String actorId) {
    throw new IllegalStateException(
        "the Studio marketplace is not deployed (orazaka.studio.enabled=false): a turn under an"
            + " installation cannot read its pack's guards");
  }
}
