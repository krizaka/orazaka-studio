package com.orazaka.studioservice.infrastructure.adapter.amqp;

import com.orazaka.studioservice.application.service.MessageDedupService;
import com.orazaka.studioservice.application.service.RunSagaService;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Advances a run's DAG when one of its jobs reaches a terminal state.
 *
 * <p>This queue sees <b>every</b> job event on the platform, including jobs submitted from chat
 * that have nothing to do with a Studio. Correlating by {@code job_id} and ignoring the misses is
 * the normal path — cheaper and more robust than a Studio-specific exchange, which would duplicate
 * the relay, the DLQ and the dedup ledger for no behavioural gain (ADR-034 §19).
 */
@Component
class JobOutcomeListener {

  private static final Logger logger = LoggerFactory.getLogger(JobOutcomeListener.class);
  private static final String CONSUMER = "studio-saga";

  private final RunSagaService runSagaService;
  private final MessageDedupService messageDedupService;

  JobOutcomeListener(RunSagaService runSagaService, MessageDedupService messageDedupService) {
    this.runSagaService = Objects.requireNonNull(runSagaService, "RunSagaService cannot be null");
    this.messageDedupService =
        Objects.requireNonNull(messageDedupService, "MessageDedupService cannot be null");
  }

  /**
   * Applies one terminal outcome.
   *
   * @param event the outcome
   * @param messageId the broker message id, used to claim the delivery exactly once
   */
  @RabbitListener(queues = "orazaka.events.studio")
  public void onJobOutcome(
      JobOutcomeEvent event,
      @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
    if (event == null || event.jobId() == null) {
      return;
    }
    if (!messageDedupService.claim(CONSUMER, messageId)) {
      logger.debug("Skipping duplicate job outcome {} for job {}", messageId, event.jobId());
      return;
    }
    try {
      runSagaService.applyOutcome(
          event.jobId(),
          event.outputOrEmpty(),
          event.consumptionOrEmpty(),
          event.model(),
          event.failed() ? event.error() : null,
          event.failed() ? event.failureCause() : null);
    } catch (RuntimeException failed) {
      // Claimed atomically, never released, was a run left non-terminal by a redelivery its own
      // failed attempt refused (ADR-067, audit #30). Re-applying is safe: the step update is
      // guarded by its status predicate, so a second application changes nothing.
      //
      // What this does NOT cover: `applyOutcome` settles the run's hold through the BILLING
      // service over HTTP. A failure after that call has already moved money, and giving the claim
      // back lets the redelivery ask again — which is safe only because the aggregate settle
      // carries an idempotency key. The transaction ends at this service's database.
      messageDedupService.release(CONSUMER, messageId);
      throw failed;
    }
  }

  /**
   * Records outcomes that could not be applied.
   *
   * <p>A lost outcome leaves a run non-terminal and its hold outstanding, so the DLQ is where that
   * becomes visible instead of silent. The sweeper is the backstop that closes the run either way.
   *
   * @param event the outcome that repeatedly failed to apply
   */
  @RabbitListener(queues = "orazaka.events.studio.dlq")
  public void onDeadLetter(JobOutcomeEvent event) {
    logger.error(
        "Job outcome dead-lettered for job {} — a run may be stranded until the sweeper closes it",
        event == null ? "unknown" : event.jobId());
  }
}
