package com.krizaka.orazaka.studioservice.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One line of an endpoint's delivery log.
 *
 * @param id the delivery (also the {@code webhook-id} of every attempt, prefixed {@code msg_})
 * @param runId the run it announces
 * @param event the event name
 * @param status {@code PENDING}, {@code DELIVERED} or {@code DEAD}
 * @param attempts attempts made
 * @param lastHttpStatus the receiver's last answer, or null
 * @param lastError the reason of the last failure, or null
 * @param createdAt when it was queued
 * @param deliveredAt when it succeeded, or null
 */
public record WebhookDeliveryView(
    UUID id,
    UUID runId,
    String event,
    String status,
    int attempts,
    Integer lastHttpStatus,
    String lastError,
    Instant createdAt,
    Instant deliveredAt) {}
