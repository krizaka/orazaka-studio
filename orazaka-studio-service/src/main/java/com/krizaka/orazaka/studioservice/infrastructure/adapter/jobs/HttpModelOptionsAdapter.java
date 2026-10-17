package com.krizaka.orazaka.studioservice.infrastructure.adapter.jobs;

import com.krizaka.orazaka.studioservice.domain.model.ModelChoice;
import com.krizaka.orazaka.studioservice.domain.port.ModelOptionsClient;
import com.krizaka.orazaka.studioservice.infrastructure.config.CapabilityRoutingProperties;
import com.krizaka.security.jwt.SessionJwtProperties;
import com.krizaka.security.token.ServiceTokenProvider;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reads a category's model options from the job service, cached for a few seconds.
 *
 * <p>Short on purpose: availability is part of the answer (a GPU host that just started), and a
 * form is opened by a person, not on a hot path. A lookup that fails is not cached for long and is
 * reported as unknown, never as an empty list — so a hiccup degrades the picker to "automatic"
 * rather than to a menu of nothing.
 */
@Component
class HttpModelOptionsAdapter implements ModelOptionsClient {

  private static final Logger logger = LoggerFactory.getLogger(HttpModelOptionsAdapter.class);

  private static final String OPTIONS_PATH = "/internal/v1/models/options?category={category}";
  private static final long ANSWER_TTL_MILLIS = 15_000;
  private static final long FAILURE_TTL_MILLIS = 3_000;

  private final RestClient jobPlaneClient;
  private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

  private record Cached(Optional<List<ModelChoice>> options, long expiresAt) {}

  HttpModelOptionsAdapter(
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
  public Optional<List<ModelChoice>> options(String category) {
    if (category == null || category.isBlank()) {
      return Optional.empty();
    }
    long now = System.currentTimeMillis();
    Cached cached = cache.get(category);
    if (cached != null && cached.expiresAt() > now) {
      return cached.options();
    }
    Optional<List<ModelChoice>> fetched = fetch(category);
    cache.put(
        category,
        new Cached(fetched, now + (fetched.isPresent() ? ANSWER_TTL_MILLIS : FAILURE_TTL_MILLIS)));
    return fetched;
  }

  private Optional<List<ModelChoice>> fetch(String category) {
    try {
      List<ModelChoice> options =
          jobPlaneClient
              .get()
              .uri(OPTIONS_PATH, category)
              .retrieve()
              .body(new ParameterizedTypeReference<List<ModelChoice>>() {});
      return Optional.ofNullable(options);
    } catch (RuntimeException e) {
      logger.warn("Model options lookup failed for category {}: {}", category, e.toString());
      return Optional.empty();
    }
  }
}
