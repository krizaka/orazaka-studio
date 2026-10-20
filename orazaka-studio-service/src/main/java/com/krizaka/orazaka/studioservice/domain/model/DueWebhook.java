package com.krizaka.orazaka.studioservice.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * One webhook delivery that is due: what to send, where, and with which secrets.
 *
 * @param id the delivery
 * @param url the receiver's address
 * @param event the event name ({@code run.completed}, {@code run.failed})
 * @param payload the JSON body, fixed when the delivery was queued so every attempt sends the same
 *     bytes
 * @param attempts attempts already made (0 on the first)
 * @param secret the endpoint's current signing secret
 * @param previousSecret the secret before the last rotation while it is still honoured, or null
 */
public record DueWebhook(
    UUID id,
    String url,
    String event,
    String payload,
    int attempts,
    String secret,
    String previousSecret) {

  public DueWebhook {
    Objects.requireNonNull(id, "id is required");
    Objects.requireNonNull(url, "url is required");
    Objects.requireNonNull(event, "event is required");
    Objects.requireNonNull(payload, "payload is required");
    Objects.requireNonNull(secret, "secret is required");
  }
}
