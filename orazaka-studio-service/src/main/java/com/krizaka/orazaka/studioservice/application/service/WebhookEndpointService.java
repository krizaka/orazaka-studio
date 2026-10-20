package com.krizaka.orazaka.studioservice.application.service;

import com.krizaka.orazaka.studioservice.domain.exception.TooManyWebhookEndpointsException;
import com.krizaka.orazaka.studioservice.domain.exception.WebhookEndpointNotFoundException;
import com.krizaka.orazaka.studioservice.domain.model.WebhookDeliveryView;
import com.krizaka.orazaka.studioservice.domain.model.WebhookEndpoint;
import com.krizaka.orazaka.studioservice.domain.port.WebhookEndpointRepository;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Manages the places a client is told about its finished runs (orazaka#189 slice B).
 *
 * <p>The signing secret is generated here, returned once and never read back. The address must be
 * {@code https}; whether it resolves to a public host is checked again at every send, because DNS
 * can change.
 */
@Service
public class WebhookEndpointService {

  static final String MAX_ENDPOINTS_KEY = "webhook.max-endpoints-per-actor";
  static final String GRACE_HOURS_KEY = "webhook.rotation-grace-hours";

  /** The events a client may subscribe to; the default is the two that end a run. */
  public static final Set<String> EVENTS = Set.of("run.completed", "run.failed", "run.cancelled");

  private static final List<String> DEFAULT_EVENTS = List.of("run.completed", "run.failed");
  private static final SecureRandom RANDOM = new SecureRandom();

  /**
   * A created endpoint and its secret, shown once.
   *
   * @param endpoint the endpoint
   * @param secret the signing secret
   */
  public record Created(WebhookEndpoint endpoint, String secret) {}

  private final WebhookEndpointRepository endpoints;
  private final StudioRuntimeConfigService config;

  public WebhookEndpointService(
      WebhookEndpointRepository endpoints, StudioRuntimeConfigService config) {
    this.endpoints = Objects.requireNonNull(endpoints, "WebhookEndpointRepository cannot be null");
    this.config = Objects.requireNonNull(config, "StudioRuntimeConfigService cannot be null");
  }

  /**
   * Creates an endpoint.
   *
   * @param actorId the owner
   * @param url the https address
   * @param events the events to subscribe to, or null for the default
   * @return the endpoint and its secret
   * @throws IllegalArgumentException when the address or an event is not acceptable
   * @throws TooManyWebhookEndpointsException when the actor holds as many as allowed
   */
  public Created create(String actorId, String url, List<String> events) {
    validateUrl(url);
    List<String> chosen = events == null || events.isEmpty() ? DEFAULT_EVENTS : validEvents(events);
    int limit = Math.max(1, config.intValue(MAX_ENDPOINTS_KEY, 5));
    if (endpoints.count(actorId) >= limit) {
      throw new TooManyWebhookEndpointsException(limit);
    }
    String secret = newSecret();
    return new Created(endpoints.create(actorId, url.strip(), chosen, secret), secret);
  }

  /**
   * The actor's endpoints.
   *
   * @param actorId the owner
   * @return the endpoints, without secrets
   */
  public List<WebhookEndpoint> list(String actorId) {
    return endpoints.list(actorId);
  }

  /**
   * Removes an endpoint.
   *
   * @param actorId the owner
   * @param id the endpoint
   * @throws WebhookEndpointNotFoundException when the actor owns no such endpoint
   */
  public void delete(String actorId, UUID id) {
    if (!endpoints.delete(actorId, id)) {
      throw new WebhookEndpointNotFoundException(id);
    }
  }

  /**
   * Issues a new secret; the old one keeps signing during the grace period so the receiver can
   * switch without losing a delivery.
   *
   * @param actorId the owner
   * @param id the endpoint
   * @return the new secret, shown once
   * @throws WebhookEndpointNotFoundException when the actor owns no such endpoint
   */
  public String rotate(String actorId, UUID id) {
    String secret = newSecret();
    Duration grace = Duration.ofHours(Math.max(0, config.intValue(GRACE_HOURS_KEY, 24)));
    if (!endpoints.rotate(actorId, id, secret, grace)) {
      throw new WebhookEndpointNotFoundException(id);
    }
    return secret;
  }

  /**
   * The newest deliveries of an endpoint.
   *
   * @param actorId the owner
   * @param id the endpoint
   * @param limit how many at most
   * @return the log
   * @throws WebhookEndpointNotFoundException when the actor owns no such endpoint
   */
  public List<WebhookDeliveryView> deliveries(String actorId, UUID id, int limit) {
    endpoints.find(actorId, id).orElseThrow(() -> new WebhookEndpointNotFoundException(id));
    return endpoints.deliveries(actorId, id, limit);
  }

  private static void validateUrl(String url) {
    if (url == null || url.isBlank() || url.length() > 2000) {
      throw new IllegalArgumentException("a webhook needs a url");
    }
    URI uri;
    try {
      uri = URI.create(url.strip());
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException("the webhook url is not a URL");
    }
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null) {
      throw new IllegalArgumentException(
          "the webhook url must be https, with a host and no credentials");
    }
  }

  private static List<String> validEvents(List<String> events) {
    Set<String> unique = new LinkedHashSet<>(events);
    for (String event : unique) {
      if (!EVENTS.contains(event)) {
        throw new IllegalArgumentException(
            "unknown webhook event '"
                + event
                + "'; choose among "
                + List.copyOf(new java.util.TreeSet<>(EVENTS)));
      }
    }
    return List.copyOf(unique);
  }

  private static String newSecret() {
    byte[] raw = new byte[24];
    RANDOM.nextBytes(raw);
    return "whsec_" + Base64.getEncoder().withoutPadding().encodeToString(raw);
  }
}
