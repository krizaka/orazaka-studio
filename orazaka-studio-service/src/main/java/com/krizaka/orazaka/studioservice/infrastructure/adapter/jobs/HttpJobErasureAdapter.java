package com.krizaka.orazaka.studioservice.infrastructure.adapter.jobs;

import com.krizaka.orazaka.studioservice.domain.exception.ErasureIncompleteException;
import com.krizaka.orazaka.studioservice.domain.port.JobErasureClient;
import com.krizaka.orazaka.studioservice.infrastructure.config.CapabilityRoutingProperties;
import com.krizaka.security.jwt.SessionJwtProperties;
import com.krizaka.security.token.ServiceTokenProvider;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Erases jobs through the job service's machine surface ({@code POST /internal/v1/jobs/erase}).
 *
 * <p>Unlike the model-options lookup next to it, a failure here is <b>never</b> degraded to
 * "nothing to do": an erasure that silently skipped the job plane would tell a person their data is
 * gone while their words sat in its tables. Any failure is raised, and the Studio service then
 * deletes nothing of its own, so the whole erasure can be asked again.
 */
@Component
class HttpJobErasureAdapter implements JobErasureClient {

  private static final String ERASE_PATH = "/internal/v1/jobs/erase";

  private final RestClient jobPlaneClient;

  HttpJobErasureAdapter(
      RestClient.Builder restClientBuilder,
      CapabilityRoutingProperties properties,
      SessionJwtProperties sessionJwt) {
    Objects.requireNonNull(properties, "CapabilityRoutingProperties cannot be null");
    ServiceTokenProvider tokens =
        new ServiceTokenProvider(sessionJwt.secret(), "orazaka-studio-service");
    this.jobPlaneClient =
        restClientBuilder
            .baseUrl(properties.baseUrl())
            .requestInitializer(request -> request.getHeaders().setBearerAuth(tokens.token()))
            .build();
  }

  @Override
  public int erase(String actorId, Collection<String> jobIds) {
    if (jobIds == null || jobIds.isEmpty()) {
      return 0;
    }
    try {
      Map<String, Object> answer =
          jobPlaneClient
              .post()
              .uri(ERASE_PATH)
              .body(Map.of("userId", actorId, "jobIds", List.copyOf(jobIds)))
              .retrieve()
              .body(new ParameterizedTypeReference<Map<String, Object>>() {});
      Object erased = answer == null ? null : answer.get("erased");
      return erased instanceof Number number ? number.intValue() : 0;
    } catch (RestClientException e) {
      // The message of a transport error never carries the body we sent, but the cause is dropped
      // anyway: what failed is "the job plane", and the ids are a person's runs.
      throw new ErasureIncompleteException(
          "the job plane could not erase the jobs of these runs; nothing was deleted", e);
    }
  }
}
