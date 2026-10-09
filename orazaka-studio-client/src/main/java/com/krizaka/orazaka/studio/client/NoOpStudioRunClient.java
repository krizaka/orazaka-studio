package com.krizaka.orazaka.studio.client;

import com.krizaka.orazaka.studio.domain.model.Run;
import com.krizaka.orazaka.studio.domain.port.StudioRunClient;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Null Object: reports that no marketplace is deployed.
 *
 * <p>Same discipline as its billing counterpart — {@code orazaka.studio.enabled=false} stays a real
 * answer instead of a branch at each call site (ERR-127). It refuses rather than pretending to
 * accept: a run that silently went nowhere would leave the caller waiting for an outcome that can
 * never arrive.
 */
final class NoOpStudioRunClient implements StudioRunClient {

  @Override
  public String start(UUID installationId, Map<String, Object> inputs) {
    throw new IllegalStateException(
        "the Studio marketplace is not deployed (orazaka.studio.enabled=false)");
  }

  @Override
  public Optional<Run> find(UUID runId) {
    return Optional.empty();
  }
}
