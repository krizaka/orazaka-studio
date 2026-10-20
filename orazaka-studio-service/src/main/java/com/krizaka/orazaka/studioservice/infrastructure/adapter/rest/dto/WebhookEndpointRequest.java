package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

import java.util.List;

/**
 * Creates a webhook endpoint.
 *
 * @param url the https address that receives the events
 * @param events the events to receive; absent means {@code run.completed} and {@code run.failed}
 */
public record WebhookEndpointRequest(String url, List<String> events) {}
