package com.krizaka.orazaka.studioservice.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A place a client wants to be told about its finished runs. The signing secret is never part of
 * this view: it is shown once, when the endpoint is created or its secret rotated.
 *
 * @param id the endpoint
 * @param url the receiver's https address
 * @param events the events it subscribed to ({@code run.completed}, {@code run.failed}, {@code
 *     run.cancelled})
 * @param enabled whether deliveries are sent
 * @param createdAt when it was created
 */
public record WebhookEndpoint(
    UUID id, String url, List<String> events, boolean enabled, Instant createdAt) {

  public WebhookEndpoint {
    events = List.copyOf(events);
  }
}
