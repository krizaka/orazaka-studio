package com.orazaka.studioservice.infrastructure.adapter.amqp;

import com.orazaka.studioservice.application.service.MessageDedupService;
import com.orazaka.studioservice.application.service.RunSagaService;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Advances a run when one of its connector steps finishes on the automation plane.
 *
 * <p>Two planes, one DAG: a step dispatched to automation reports here, a step dispatched to the
 * job service reports on {@code job.*.done}, and the saga correlates both by the same job id. The
 * interpreter therefore never learns which plane ran a step — which is what lets a blueprint mix
 * CAPABILITY and CONNECTOR steps freely.
 */
@Component
class ConnectorOutcomeListener {

  private static final Logger logger = LoggerFactory.getLogger(ConnectorOutcomeListener.class);
  private static final String CONSUMER = "studio-connector";

  private final RunSagaService runSagaService;
  private final MessageDedupService messageDedupService;

  ConnectorOutcomeListener(RunSagaService runSagaService, MessageDedupService messageDedupService) {
    this.runSagaService = Objects.requireNonNull(runSagaService, "RunSagaService cannot be null");
    this.messageDedupService =
        Objects.requireNonNull(messageDedupService, "MessageDedupService cannot be null");
  }

  /**
   * Applies one terminal connector outcome.
   *
   * @param event the telemetry
   * @param messageId the broker message id, used to claim the delivery exactly once
   */
  @RabbitListener(queues = "orazaka.events.studio.connector")
  public void onConnectorOutcome(
      ConnectorOutcomeEvent event,
      @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
    if (event == null || event.jobId() == null || !event.terminal()) {
      // A RUNNING heartbeat is not an outcome; ignoring it is the normal path.
      return;
    }
    if (!messageDedupService.claim(CONSUMER, messageId)) {
      logger.debug(
          "Skipping duplicate connector telemetry {} for job {}", messageId, event.jobId());
      return;
    }
    try {
      runSagaService.applyOutcome(
          event.jobId(),
          Map.of("status", String.valueOf(event.status())),
          event.failed() ? String.valueOf(event.message()) : null);
    } catch (RuntimeException failed) {
      // Same shape as the job outcome it sits beside (ADR-067, audit #30): a claim kept after a
      // failure leaves the run non-terminal and its hold outstanding for good.
      messageDedupService.release(CONSUMER, messageId);
      throw failed;
    }
  }
}
