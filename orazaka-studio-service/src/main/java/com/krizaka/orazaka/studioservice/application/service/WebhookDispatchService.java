package com.krizaka.orazaka.studioservice.application.service;

import com.krizaka.orazaka.studioservice.domain.model.DueWebhook;
import com.krizaka.orazaka.studioservice.domain.policy.WebhookSignature;
import com.krizaka.orazaka.studioservice.domain.port.WebhookDeliveryStore;
import com.krizaka.orazaka.studioservice.domain.port.WebhookSender;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Tells clients their run is over (orazaka#189 slice B, ADR-083): queues a delivery for each
 * finished run that has an endpoint, sends the due ones signed, and retries a failure with an
 * exponential backoff until it gives up.
 *
 * <p>At-least-once: a receiver may see one event twice (a lost answer, a crashed sweep) and dedupes
 * on {@code webhook-id}, which is the delivery's id and does not change between retries. Every
 * number is a {@code studio_runtime_config} row (AGENTS.md §4): how often, how long, how many.
 */
@Service
public class WebhookDispatchService {

  static final String MAX_ATTEMPTS_KEY = "webhook.max-attempts";
  static final String BACKOFF_KEY = "webhook.backoff-seconds";
  static final String BACKOFF_CAP_KEY = "webhook.backoff-cap-seconds";
  static final String TIMEOUT_KEY = "webhook.timeout-seconds";
  static final String BATCH_KEY = "webhook.batch-size";

  private final WebhookDeliveryStore deliveries;
  private final WebhookSender sender;
  private final StudioRuntimeConfigService config;
  private final Clock clock;

  @Autowired
  public WebhookDispatchService(
      WebhookDeliveryStore deliveries, WebhookSender sender, StudioRuntimeConfigService config) {
    this(deliveries, sender, config, Clock.systemUTC());
  }

  WebhookDispatchService(
      WebhookDeliveryStore deliveries,
      WebhookSender sender,
      StudioRuntimeConfigService config,
      Clock clock) {
    this.deliveries = Objects.requireNonNull(deliveries, "WebhookDeliveryStore cannot be null");
    this.sender = Objects.requireNonNull(sender, "WebhookSender cannot be null");
    this.config = Objects.requireNonNull(config, "StudioRuntimeConfigService cannot be null");
    this.clock = Objects.requireNonNull(clock, "Clock cannot be null");
  }

  /**
   * One sweep: queue what finished, send what is due.
   *
   * @return how many deliveries were attempted
   */
  public int sweep() {
    deliveries.enqueueFinishedRuns();
    int timeoutSeconds = Math.max(1, config.intValue(TIMEOUT_KEY, 10));
    int batch = Math.max(1, config.intValue(BATCH_KEY, 20));
    Duration timeout = Duration.ofSeconds(timeoutSeconds);
    // The lease outlives one attempt, so another instance does not repeat it while it is in flight.
    Duration lease = timeout.plusSeconds(30);
    List<DueWebhook> due = deliveries.claimDue(batch, lease);
    due.forEach(webhook -> attempt(webhook, timeout));
    return due.size();
  }

  private void attempt(DueWebhook webhook, Duration timeout) {
    var headers =
        WebhookSignature.headers(
            webhook.secret(),
            webhook.previousSecret(),
            "msg_" + webhook.id(),
            clock.instant().getEpochSecond(),
            webhook.payload());
    Integer status = null;
    String error;
    try {
      status = sender.post(webhook.url(), headers, webhook.payload(), timeout);
      if (status >= 200 && status < 300) {
        deliveries.delivered(webhook.id(), status);
        return;
      }
      error = "the receiver answered " + status;
    } catch (IOException unreachable) {
      error = "not delivered: " + abbreviate(unreachable.getMessage());
    }
    fail(webhook, status, error);
  }

  private void fail(DueWebhook webhook, Integer status, String error) {
    int attemptsMade = webhook.attempts() + 1;
    int max = Math.max(1, config.intValue(MAX_ATTEMPTS_KEY, 8));
    if (attemptsMade >= max) {
      deliveries.dead(webhook.id(), status, error);
    } else {
      deliveries.retryLater(webhook.id(), status, error, backoff(attemptsMade));
    }
  }

  /**
   * The wait after the {@code attemptsMade}-th failure: the base, doubled each time, capped.
   *
   * @param attemptsMade failures so far, from 1
   * @return the wait
   */
  Duration backoff(int attemptsMade) {
    long base = Math.max(1, config.intValue(BACKOFF_KEY, 30));
    long cap = Math.max(base, config.intValue(BACKOFF_CAP_KEY, 3600));
    long seconds = base;
    for (int i = 1; i < attemptsMade && seconds < cap; i++) {
      seconds *= 2;
    }
    return Duration.ofSeconds(Math.min(seconds, cap));
  }

  private static String abbreviate(String message) {
    if (message == null) {
      return "no detail";
    }
    return message.length() > 200 ? message.substring(0, 200) : message;
  }
}
