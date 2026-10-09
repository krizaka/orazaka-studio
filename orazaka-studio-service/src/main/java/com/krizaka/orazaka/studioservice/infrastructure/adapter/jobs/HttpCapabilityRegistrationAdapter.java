package com.krizaka.orazaka.studioservice.infrastructure.adapter.jobs;

import com.krizaka.orazaka.jobs.domain.exception.CapabilityRegistrationException;
import com.krizaka.orazaka.jobs.domain.model.CapabilityDeclaration;
import com.krizaka.orazaka.jobs.domain.port.CapabilityRegistrationClient;
import com.krizaka.orazaka.studioservice.infrastructure.config.CapabilityRoutingProperties;
import com.krizaka.security.jwt.SessionJwtProperties;
import com.krizaka.security.token.ServiceTokenProvider;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Writes the capability rows a pack contributes, over the job service's {@code /internal/v1}
 * surface.
 *
 * <p>The write twin of {@code HttpCapabilityRoutingAdapter}, and shaped as its opposite in the one
 * way that matters: <b>no cache and no soft failure</b>. The read adapter caches because it sits on
 * the dispatch path of every step of every run; this one is called once per install. The read
 * adapter returns empty on an outage and lets the caller refuse a dispatch; this one throws,
 * because a capability that was silently not written leaves a pack whose blueprints dispatch
 * nowhere — and the first person to see it is a user paying for a run that cannot start.
 */
@Component
class HttpCapabilityRegistrationAdapter implements CapabilityRegistrationClient {

  private static final Logger logger =
      LoggerFactory.getLogger(HttpCapabilityRegistrationAdapter.class);

  private static final String CAPABILITY_PATH = "/internal/v1/capabilities/{featureKey}";

  private final RestClient jobPlaneClient;

  HttpCapabilityRegistrationAdapter(
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
  public void register(CapabilityDeclaration declaration) {
    Objects.requireNonNull(declaration, "declaration must not be null");
    try {
      jobPlaneClient
          .put()
          .uri(CAPABILITY_PATH, declaration.featureKey())
          .body(declaration)
          .retrieve()
          .toBodilessEntity();
      logger.info(
          "Registered capability {} → {} / {}",
          declaration.featureKey(),
          declaration.routingKey(),
          declaration.handlerKey());
    } catch (RestClientException e) {
      throw new CapabilityRegistrationException(
          "Could not register capability " + declaration.featureKey(), e);
    }
  }

  @Override
  public boolean unregister(String featureKey) {
    if (featureKey == null || featureKey.isBlank()) {
      return false;
    }
    try {
      return Boolean.TRUE.equals(
          jobPlaneClient
              .delete()
              .uri(CAPABILITY_PATH, featureKey)
              .exchange((request, response) -> response.getStatusCode().is2xxSuccessful(), false));
    } catch (RestClientException e) {
      // Compensation path: logged, never thrown. It runs while an install failure is already
      // being reported, and throwing would replace that error with this one.
      logger.error("Could not unregister capability {} while compensating", featureKey, e);
      return false;
    }
  }
}
