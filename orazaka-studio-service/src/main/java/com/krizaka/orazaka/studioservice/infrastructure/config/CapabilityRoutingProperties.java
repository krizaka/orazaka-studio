package com.krizaka.orazaka.studioservice.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Wiring of the job service's capability-routing surface ({@code
 * orazaka.studio-service.job-plane}).
 *
 * <p>Infrastructure only: a base URL and a staleness bound. The routing table itself is data in
 * {@code orazaka_capabilities} and must never appear here — a routing key in yaml would put the
 * mapping back in two places, which is the whole of what ADR-037 undid (AGENTS.md §4).
 *
 * @param baseUrl the job service's base URL
 * @param cacheTtl how long a resolved route is reused before it is read again; the table changes at
 *     admin speed, so this bounds how long a newly enabled capability stays unroutable
 */
@ConfigurationProperties(prefix = "orazaka.studio-service.job-plane")
public record CapabilityRoutingProperties(
    String baseUrl, @DefaultValue("PT60S") Duration cacheTtl) {

  /** Compact canonical constructor enforcing the wiring's invariants (ERR-106). */
  public CapabilityRoutingProperties {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("job-plane base-url is required");
    }
    if (cacheTtl == null || cacheTtl.isNegative() || cacheTtl.isZero()) {
      throw new IllegalArgumentException("job-plane cache-ttl must be positive");
    }
  }
}
