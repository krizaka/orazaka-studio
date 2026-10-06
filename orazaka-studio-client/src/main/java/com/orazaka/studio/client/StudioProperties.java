package com.orazaka.studio.client;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Consumer-side Studio wiring ({@code orazaka.studio}).
 *
 * <p>A <b>bootstrap</b> switch, not a policy one: it decides whether the HTTP adapters are wired at
 * all. What Studios exist, what they cost and who may run them are rows owned by the studio service
 * and are never duplicated here (AGENTS.md §4).
 *
 * <p>Default {@code false}: a fresh clone runs the whole stack without the marketplace, and a
 * consumer that asks for a Studio run simply gets told there is none.
 *
 * <p>Timeouts are looser than billing's on purpose. Starting a run is not a hot-path precondition —
 * it returns an id and the work happens on the broker — so a second of latency is a cost worth
 * paying for a request that will run for minutes.
 *
 * @param enabled whether the HTTP adapters are wired instead of the no-ops
 * @param baseUrl the studio service's internal base URL
 * @param connectTimeout how long to wait for the connection
 * @param readTimeout how long to wait for the response
 */
@ConfigurationProperties(prefix = "orazaka.studio")
public record StudioProperties(
    boolean enabled,
    String baseUrl,
    Duration connectTimeout,
    Duration readTimeout,
    String serviceSecret) {

  /** Compact canonical constructor supplying defaults (ERR-106). */
  public StudioProperties {
    baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "http://localhost:8096" : baseUrl;
    connectTimeout = connectTimeout == null ? Duration.ofSeconds(1) : connectTimeout;
    readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
  }
}
