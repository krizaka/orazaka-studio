package com.krizaka.orazaka.studioservice.domain.port;

import com.krizaka.orazaka.studioservice.domain.model.DueWebhook;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * The queue and log of webhook deliveries (orazaka#189 slice B).
 *
 * <p>Several instances sweep the same table: queueing is idempotent per (endpoint, run, event) and
 * a claim leases a delivery, so one receiver is not called twice for one attempt.
 */
public interface WebhookDeliveryStore {

  /**
   * Queues a delivery for every finished run of an actor that has an enabled endpoint subscribed to
   * the run's event, once.
   *
   * @return how many deliveries were queued
   */
  int enqueueFinishedRuns();

  /**
   * Takes the deliveries that are due and leases them: until the lease ends no other sweep returns
   * them, so a crashed instance only delays them.
   *
   * @param limit how many at most
   * @param lease how long the claim holds
   * @return the due deliveries
   */
  List<DueWebhook> claimDue(int limit, Duration lease);

  /**
   * Records a success.
   *
   * @param id the delivery
   * @param httpStatus the receiver's answer
   */
  void delivered(UUID id, int httpStatus);

  /**
   * Records a failed attempt and schedules the next one.
   *
   * @param id the delivery
   * @param httpStatus the receiver's answer, or null when none came
   * @param error a short reason, never a secret
   * @param retryIn the wait before the next attempt
   */
  void retryLater(UUID id, Integer httpStatus, String error, Duration retryIn);

  /**
   * Gives up: the delivery stays in the log as {@code DEAD}.
   *
   * @param id the delivery
   * @param httpStatus the receiver's last answer, or null
   * @param error a short reason
   */
  void dead(UUID id, Integer httpStatus, String error);
}
