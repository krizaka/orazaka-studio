package com.orazaka.studioservice.infrastructure.adapter.jobs;

import com.krizaka.security.jwt.SessionJwtProperties;
import com.krizaka.security.token.ServiceTokenProvider;
import com.orazaka.jobs.domain.model.CapabilityRoute;
import com.orazaka.jobs.domain.port.CapabilityRoutingClient;
import com.orazaka.studioservice.infrastructure.config.CapabilityRoutingProperties;
import com.orazaka.studioservice.infrastructure.support.CapabilityRouteCache;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reads a capability's route from the job service, cached to a short TTL.
 *
 * <p>The Studio context owns {@code orazaka_studio_db} and the job plane owns {@code
 * orazaka_capabilities}; a cross-context read of that table would be exactly the coupling
 * SEAM-001/002 forbid, so the route arrives over the job service's {@code /internal/v1} surface.
 *
 * <p><b>Cached because it sits on the dispatch hot path and the table changes at admin speed</b> —
 * every step of every fan-out would otherwise be a network hop to learn a value that changes when
 * someone edits a row. Same shape and same reasons as {@code HttpEntitlementProvider}: a bounded
 * TTL, a {@link ConcurrentHashMap}, no new caching library.
 *
 * <p><b>The TTL is a floor, not the mechanism.</b> A capability row change is announced on {@code
 * evt.capability.changed} and evicted here on arrival (ADR-038), so disabling a capability takes
 * effect on the instant rather than at the end of a cache window. Before that event existed, {@code
 * is_enabled = false} was an eventual kill switch — tolerable for cost, not for a REGULATED pack
 * that must stop serving the moment it is withdrawn.
 *
 * <p><b>Does not fail open.</b> The entitlement provider may fail open because the credit hold is a
 * second gate behind it; there is no second gate here. A route this adapter cannot resolve —
 * absent, disabled, or the job service unreachable — is returned as empty, and the caller refuses
 * the dispatch. Substituting a plausible queue on an outage is the silent misroute ADR-037 removed,
 * and an outage is exactly when nobody is watching for it.
 */
@Component
class HttpCapabilityRoutingAdapter implements CapabilityRoutingClient, CapabilityRouteCache {

  private static final Logger logger = LoggerFactory.getLogger(HttpCapabilityRoutingAdapter.class);

  private static final String ROUTE_PATH = "/internal/v1/capabilities/{featureKey}/route";

  /**
   * How soon to retry after a failed lookup — an outage is not an answer.
   *
   * <p>Distinct from the configured TTL on purpose, and for the same reason {@code
   * HttpEntitlementProvider} draws the same distinction. A 404 is authoritative ("no enabled
   * route") and is worth caching for the full TTL. An unreachable job service is not: caching it
   * would let one dropped connection refuse every step of every run for a minute, turning a blip
   * into an outage.
   */
  private static final Duration RETRY_AFTER = Duration.ofSeconds(5);

  private final RestClient jobPlaneClient;
  private final long cacheTtlMillis;
  private final ConcurrentHashMap<String, CachedRoute> cache = new ConcurrentHashMap<>();

  /** A resolution and when it goes stale; {@code route} is null for "no route", which is cached. */
  private record CachedRoute(CapabilityRoute route, long expiresAt) {}

  HttpCapabilityRoutingAdapter(
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
    this.cacheTtlMillis = properties.cacheTtl().toMillis();
  }

  /**
   * Drops a capability's cached resolution, so the next dispatch reads the current row.
   *
   * <p>Called when {@code evt.capability.changed} says the row moved. The TTL alone would get there
   * eventually; "eventually" is the window in which a withdrawn capability keeps being dispatched.
   *
   * @param featureKey the capability whose row changed
   */
  @Override
  public void evict(String featureKey) {
    if (featureKey != null && cache.remove(featureKey) != null) {
      logger.info("Evicted cached route for {} — its capability row changed", featureKey);
    }
  }

  @Override
  public Optional<CapabilityRoute> route(String featureKey) {
    if (featureKey == null || featureKey.isBlank()) {
      return Optional.empty();
    }
    long now = System.currentTimeMillis();
    CachedRoute cached = cache.get(featureKey);
    if (cached != null && cached.expiresAt() > now) {
      return Optional.ofNullable(cached.route());
    }
    Lookup lookup = fetch(featureKey);
    long ttl = lookup.answered() ? cacheTtlMillis : RETRY_AFTER.toMillis();
    cache.put(featureKey, new CachedRoute(lookup.route(), now + ttl));
    return Optional.ofNullable(lookup.route());
  }

  /**
   * What one lookup produced.
   *
   * <p>{@code answered} separates "the registry says there is no route" from "the registry could
   * not be reached". Both refuse the dispatch — that part is not negotiable — but only the first
   * deserves to be remembered for a minute.
   */
  private record Lookup(CapabilityRoute route, boolean answered) {}

  /**
   * One lookup. Every non-2xx — including the 404 that means "no enabled route" — is an answer of
   * "none"; only a transport failure is not an answer at all.
   */
  private Lookup fetch(String featureKey) {
    try {
      return new Lookup(
          jobPlaneClient
              .get()
              .uri(ROUTE_PATH, featureKey)
              .exchange(
                  (request, response) ->
                      response.getStatusCode().is2xxSuccessful()
                          ? response.bodyTo(CapabilityRoute.class)
                          : null),
          true);
    } catch (RuntimeException e) {
      logger.error(
          "Capability route lookup failed for {} — the step will be refused, not rerouted",
          featureKey,
          e);
      return new Lookup(null, false);
    }
  }
}
