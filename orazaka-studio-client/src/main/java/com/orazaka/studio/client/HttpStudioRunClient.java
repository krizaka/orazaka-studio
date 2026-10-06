package com.orazaka.studio.client;

import com.orazaka.studio.domain.model.Run;
import com.orazaka.studio.domain.port.StudioRunClient;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Starts and reads runs over the studio service's own REST surface.
 *
 * <p>HTTP rather than the broker, deliberately, and it does not contradict "the exchanges are the
 * API" (ADR-032): that rule governs how a run's <b>steps</b> are executed. Accepting a run is a
 * synchronous request/response — the caller needs the run id, the entitlement verdict and any
 * credit refusal back on the same call — so it is exactly the interactive shape AGENTS.md §6 says
 * stays synchronous.
 */
final class HttpStudioRunClient implements StudioRunClient {

  private static final Logger logger = LoggerFactory.getLogger(HttpStudioRunClient.class);

  private final RestClient restClient;

  HttpStudioRunClient(RestClient restClient) {
    this.restClient = Objects.requireNonNull(restClient, "RestClient cannot be null");
  }

  @Override
  public String start(UUID installationId, Map<String, Object> inputs) {
    Run accepted =
        restClient
            .post()
            .uri("/api/v1/studios/installations/{id}/runs", installationId)
            .body(Map.of("inputs", inputs == null ? Map.of() : inputs))
            .retrieve()
            .body(Run.class);
    if (accepted == null) {
      throw new IllegalStateException("studio service accepted the run but returned nothing");
    }
    return accepted.id().toString();
  }

  @Override
  public Optional<Run> find(UUID runId) {
    try {
      return Optional.ofNullable(
          restClient.get().uri("/api/v1/studios/runs/{id}", runId).retrieve().body(Run.class));
    } catch (RestClientException unreachable) {
      // A run that cannot be read is not a run that failed. Callers poll; an empty answer makes
      // them try again, whereas a thrown exception would surface a transient blip as a dead run.
      logger.warn("Could not read run {}: {}", runId, unreachable.getMessage());
      return Optional.empty();
    }
  }
}
