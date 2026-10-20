package com.krizaka.orazaka.studioservice.domain.port;

import com.krizaka.orazaka.studioservice.domain.model.WebhookDeliveryView;
import com.krizaka.orazaka.studioservice.domain.model.WebhookEndpoint;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The webhook endpoints of an actor (orazaka#189 slice B). Every read and write is scoped to the
 * actor.
 */
public interface WebhookEndpointRepository {

  /**
   * Counts the actor's endpoints.
   *
   * @param actorId the opaque billable subject
   * @return how many
   */
  int count(String actorId);

  /**
   * Stores a new endpoint.
   *
   * @param actorId the owner
   * @param url the https address
   * @param events the subscribed events
   * @param secret the signing secret
   * @return the endpoint
   */
  WebhookEndpoint create(String actorId, String url, List<String> events, String secret);

  /**
   * The actor's endpoints, oldest first.
   *
   * @param actorId the owner
   * @return the endpoints
   */
  List<WebhookEndpoint> list(String actorId);

  /**
   * Removes an endpoint and its log.
   *
   * @param actorId the owner
   * @param id the endpoint
   * @return {@code false} when the actor owns no such endpoint
   */
  boolean delete(String actorId, UUID id);

  /**
   * Replaces the signing secret; the old one keeps signing for {@code grace}.
   *
   * @param actorId the owner
   * @param id the endpoint
   * @param secret the new secret
   * @param grace how long the previous secret is still honoured
   * @return {@code false} when the actor owns no such endpoint
   */
  boolean rotate(String actorId, UUID id, String secret, Duration grace);

  /**
   * The newest deliveries of an endpoint.
   *
   * @param actorId the owner
   * @param id the endpoint
   * @param limit how many at most
   * @return the log, newest first; empty when the actor owns no such endpoint
   */
  List<WebhookDeliveryView> deliveries(String actorId, UUID id, int limit);

  /**
   * An endpoint, if the actor owns it.
   *
   * @param actorId the owner
   * @param id the endpoint
   * @return the endpoint
   */
  Optional<WebhookEndpoint> find(String actorId, UUID id);
}
