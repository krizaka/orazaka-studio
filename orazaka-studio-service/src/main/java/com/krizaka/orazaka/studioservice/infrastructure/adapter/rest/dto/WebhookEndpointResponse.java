package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.krizaka.orazaka.studioservice.domain.model.WebhookEndpoint;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A webhook endpoint. {@code secret} is present only in the answer that creates it or rotates its
 * secret, and cannot be read again.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebhookEndpointResponse(
    UUID id, String url, List<String> events, boolean enabled, Instant createdAt, String secret) {

  public static WebhookEndpointResponse from(WebhookEndpoint endpoint) {
    return of(endpoint, null);
  }

  public static WebhookEndpointResponse of(WebhookEndpoint endpoint, String secret) {
    return new WebhookEndpointResponse(
        endpoint.id(),
        endpoint.url(),
        endpoint.events(),
        endpoint.enabled(),
        endpoint.createdAt(),
        secret);
  }
}
