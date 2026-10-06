package com.orazaka.studio.domain.port;

import com.orazaka.studio.domain.model.Run;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Write side of the Studio contract: execute an installation.
 *
 * <p>Starting a run is asynchronous by construction — it returns an id, not a result, because a run
 * is a saga of durable jobs that can last minutes. Progress reaches the browser over the job
 * plane's existing SSE relay; implementations must not open a second stream for it.
 */
public interface StudioRunClient {

  /**
   * Starts a run of an installation.
   *
   * @param installationId the installation to execute
   * @param inputs the actor's answers, validated server-side against the blueprint's JSON Schema
   *     before any credit is held — the client-side form is convenience, not enforcement
   * @return the id of the accepted run
   * @throws com.orazaka.studio.domain.exception.StudioNotEntitledException when the actor's plan
   *     does not grant this Studio — distinct from having no credits, and the UI must say which
   */
  String start(UUID installationId, Map<String, Object> inputs);

  /**
   * Resolves one run by id.
   *
   * @param runId the run's identity
   * @return the run, or empty when no such run is visible to the caller
   */
  Optional<Run> find(UUID runId);
}
