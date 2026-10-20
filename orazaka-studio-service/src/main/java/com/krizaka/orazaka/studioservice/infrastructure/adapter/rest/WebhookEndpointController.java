package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.studioservice.application.service.WebhookEndpointService;
import com.krizaka.orazaka.studioservice.domain.model.WebhookDeliveryView;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto.WebhookEndpointRequest;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto.WebhookEndpointResponse;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where a client is told that its runs are over (orazaka#189 slice B): the webhook endpoints of the
 * caller. Each delivery is signed (Standard Webhooks: {@code webhook-id}, {@code
 * webhook-timestamp}, {@code webhook-signature: v1,<base64 HMAC-SHA256>}) with the endpoint's
 * secret, which is shown once, when it is created or rotated.
 */
@RestController
@RequestMapping("/api/v1/studios/webhooks")
class WebhookEndpointController {

  private static final int DEFAULT_LOG = 50;

  private final WebhookEndpointService webhooks;

  WebhookEndpointController(WebhookEndpointService webhooks) {
    this.webhooks = Objects.requireNonNull(webhooks, "WebhookEndpointService cannot be null");
  }

  /**
   * Creates an endpoint.
   *
   * @param request the address and, optionally, the events
   * @param actor the authenticated caller
   * @return {@code 201} with the endpoint and its secret, shown once
   */
  @PostMapping
  ResponseEntity<WebhookEndpointResponse> create(
      @RequestBody WebhookEndpointRequest request, @AuthenticationPrincipal Jwt actor) {
    WebhookEndpointService.Created created =
        webhooks.create(actor.getSubject(), request.url(), request.events());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(WebhookEndpointResponse.of(created.endpoint(), created.secret()));
  }

  /**
   * The caller's endpoints (no secrets).
   *
   * @param actor the authenticated caller
   * @return the endpoints
   */
  @GetMapping
  List<WebhookEndpointResponse> list(@AuthenticationPrincipal Jwt actor) {
    return webhooks.list(actor.getSubject()).stream().map(WebhookEndpointResponse::from).toList();
  }

  /**
   * Removes an endpoint and its log.
   *
   * @param id the endpoint
   * @param actor the authenticated caller
   * @return {@code 204}
   */
  @DeleteMapping("/{id}")
  ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt actor) {
    webhooks.delete(actor.getSubject(), id);
    return ResponseEntity.noContent().build();
  }

  /**
   * Issues a new secret. The previous one keeps signing next to it for the grace period, so the
   * receiver can switch without losing a delivery.
   *
   * @param id the endpoint
   * @param actor the authenticated caller
   * @return the new secret, shown once
   */
  @PostMapping("/{id}/rotate")
  Map<String, String> rotate(@PathVariable UUID id, @AuthenticationPrincipal Jwt actor) {
    return Map.of("secret", webhooks.rotate(actor.getSubject(), id));
  }

  /**
   * The delivery log of an endpoint, newest first.
   *
   * @param id the endpoint
   * @param limit how many at most (default 50, up to 200)
   * @param actor the authenticated caller
   * @return the log
   */
  @GetMapping("/{id}/deliveries")
  List<WebhookDeliveryView> deliveries(
      @PathVariable UUID id,
      @RequestParam(required = false) Integer limit,
      @AuthenticationPrincipal Jwt actor) {
    int effective = limit == null || limit < 1 ? DEFAULT_LOG : Math.min(limit, 200);
    return webhooks.deliveries(actor.getSubject(), id, effective);
  }
}
