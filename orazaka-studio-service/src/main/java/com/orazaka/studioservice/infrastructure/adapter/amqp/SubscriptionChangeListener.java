package com.orazaka.studioservice.infrastructure.adapter.amqp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.krizaka.messaging.dedup.MessageDedup;
import com.orazaka.studioservice.application.service.InstallationLifecycleService;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Pauses and resumes an actor's Studios as their subscription changes (design §14).
 *
 * <p>Without this, a lapsed subscription leaves installations ACTIVE and runnable: the entitlement
 * gate would refuse each run one at a time, which reads to the user as the product breaking rather
 * than as a bill to settle. Pausing states the reason once, in the place they look.
 */
@Component
class SubscriptionChangeListener {

  private static final Logger logger = LoggerFactory.getLogger(SubscriptionChangeListener.class);
  private static final String CONSUMER = "studio-subscription";

  /** Statuses under which an actor keeps their workspace. Anything else pauses it. */
  private static final Set<String> ENTITLED = Set.of("ACTIVE", "TRIALING");

  private final InstallationLifecycleService lifecycleService;
  private final MessageDedup messageDedupService;

  SubscriptionChangeListener(
      InstallationLifecycleService lifecycleService, MessageDedup messageDedupService) {
    this.lifecycleService = Objects.requireNonNull(lifecycleService, "lifecycle required");
    this.messageDedupService =
        Objects.requireNonNull(messageDedupService, "MessageDedup cannot be null");
  }

  /**
   * Applies one commercial change.
   *
   * @param event the announcement
   * @param messageId the broker message id, used to claim the delivery exactly once
   */
  @RabbitListener(queues = "orazaka.events.studio.subscription")
  public void onSubscriptionChanged(
      SubscriptionEvent event,
      @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
    if (event == null || event.actorId() == null || event.status() == null) {
      // A malformed announcement must not take the listener down, and must not guess: pausing an
      // actor's workspace on a payload we cannot read would be a self-inflicted outage.
      logger.warn("Subscription event carried no actor or status — ignoring");
      return;
    }
    if (!messageDedupService.claim(CONSUMER, messageId)) {
      return;
    }
    try {
      if (ENTITLED.contains(event.status())) {
        lifecycleService.resumeFor(event.actorId());
      } else {
        lifecycleService.pauseFor(event.actorId());
      }
    } catch (RuntimeException failed) {
      // Or an actor whose plan changed keeps the installations that change should have paused,
      // because the redelivery is refused by this attempt's own claim (ADR-067, audit #30).
      // Re-applying is safe: both calls set a state rather than stepping one.
      messageDedupService.release(CONSUMER, messageId);
      throw failed;
    }
  }

  /**
   * The commercial announcement, as this service reads it.
   *
   * <p>Contract copy of what the billing service publishes, and a tolerant reader: the same event
   * feeds the entitlement cache and the analytics plane, each reading keys the others ignore.
   *
   * @param actorId the opaque billable subject
   * @param planKey the plan now in force
   * @param status the subscription's new status
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record SubscriptionEvent(String actorId, String planKey, String status) {}
}
