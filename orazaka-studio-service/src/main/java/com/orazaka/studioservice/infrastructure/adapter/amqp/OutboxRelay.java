package com.orazaka.studioservice.infrastructure.adapter.amqp;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains the transactional outbox onto the exchange each row names.
 *
 * <p>The second half of the outbox pattern: events are committed with the state they describe, then
 * delivered from here. A broker outage therefore defers delivery instead of losing an event or
 * announcing something that rolled back (AGENTS.md §6).
 *
 * <p>Failures back off exponentially and stay in the table rather than being dropped, because an
 * unpublished {@code evt.studio.run.succeeded} means a welcome email that never arrives — invisible
 * unless the row survives to be retried.
 *
 * <p>Since ADR-067 the table also carries <b>step dispatches</b>, and those are published the way
 * the persistence relay publishes a job command: raw JSON bytes with {@code messageId} set on the
 * message properties, because the job service dedups deliveries by that id. The poll interval
 * matches door 1's for the same reason — a dispatch waits for this relay now, and the two doors
 * should not differ on how long.
 */
@Component
class OutboxRelay {

  private static final Logger logger = LoggerFactory.getLogger(OutboxRelay.class);
  private static final int BATCH_SIZE = 50;

  private final JdbcTemplate jdbcTemplate;
  private final RabbitTemplate rabbitTemplate;

  OutboxRelay(JdbcTemplate jdbcTemplate, RabbitTemplate rabbitTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
    this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "RabbitTemplate cannot be null");
  }

  /** Publishes the next batch of pending events. */
  @Scheduled(fixedDelayString = "${orazaka.studio-service.outbox.interval:500}")
  void drain() {
    List<PendingEvent> pending =
        jdbcTemplate.query(
            "SELECT id, aggregate_id, event_type, exchange, message_id, payload FROM studio_outbox"
                + " WHERE published_at IS NULL AND next_attempt_at <= now()"
                + " ORDER BY created_at LIMIT "
                + BATCH_SIZE
                // The claim. Without it this relay SELECTs, publishes, and only then marks — so a
                // second instance reading between the select and the mark publishes the same row
                // again. The other three relays all claim; this one did not (ADR-058 §3).
                + " FOR UPDATE SKIP LOCKED",
            (rs, rowNum) ->
                new PendingEvent(
                    rs.getObject("id", UUID.class),
                    rs.getString("aggregate_id"),
                    rs.getString("event_type"),
                    rs.getString("exchange"),
                    rs.getString("message_id"),
                    rs.getString("payload")));

    for (PendingEvent event : pending) {
      try {
        rabbitTemplate.send(event.exchange(), event.eventType(), toAmqpMessage(event));
        jdbcTemplate.update(
            "UPDATE studio_outbox SET published_at = now() WHERE id = ?", event.id());
      } catch (RuntimeException failure) {
        logger.warn("Could not publish outbox event {} — backing off", event.id(), failure);
        jdbcTemplate.update(
            "UPDATE studio_outbox SET attempts = attempts + 1,"
                + " next_attempt_at = now() + (INTERVAL '5 seconds' * POWER(2, LEAST(attempts, 6)))"
                + " WHERE id = ?",
            event.id());
      }
    }
  }

  /**
   * One undelivered row, as the relay publishes it.
   *
   * <p>Built here rather than handed to {@code convertAndSend} so the {@code messageId} survives: a
   * step dispatch redelivered without it is a step the job service cannot recognise as a duplicate,
   * which is the difference between an idempotent retry and a second image generated.
   */
  private Message toAmqpMessage(PendingEvent event) {
    MessageProperties properties = new MessageProperties();
    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    if (event.messageId() != null) {
      properties.setMessageId(event.messageId());
    }
    // spring-amqp 4.0.0 NPEs in SimpleAmqpHeaderMapper.toHeaders on a null AMQP priority; the
    // persistence relay sets an explicit 0 for the same reason.
    properties.setPriority(0);
    return new Message(
        event.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8), properties);
  }

  /** One undelivered event or command. */
  private record PendingEvent(
      UUID id,
      String aggregateId,
      String eventType,
      String exchange,
      String messageId,
      String payload) {}
}
