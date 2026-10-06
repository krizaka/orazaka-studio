package com.orazaka.studioservice.application.service;

import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Appends domain events <b>and step dispatches</b> to the transactional outbox (AGENTS.md §6).
 *
 * <p>Written inside the caller's transaction, relayed afterwards. Publishing directly from a
 * transaction is the dual-write bug: a broker hiccup would either lose an event whose state was
 * committed, or announce a run that rolled back.
 *
 * <p><b>A command is the worse half of that bug, which is why it is here too</b> (ADR-067). The run
 * path was the one producer in Orazaka publishing straight from inside a transaction: a rollback
 * after {@code convertAndSend} left a worker executing against a step row that no longer existed,
 * and the credit hold it had taken lives in ANOTHER service, so the rollback could not reach it —
 * hold taken, run gone, model burning, aggregate settle never called.
 */
@Service
public class OutboxService {

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public OutboxService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper cannot be null");
  }

  /**
   * Appends one event.
   *
   * @param aggregateId what the event is about — a run id, an installation id
   * @param eventType the routing key, e.g. {@code evt.studio.run.succeeded}
   * @param payload the event body
   */
  public void append(String aggregateId, String eventType, Map<String, Object> payload) {
    jdbcTemplate.update(
        "INSERT INTO studio_outbox (aggregate_id, event_type, payload) VALUES (?, ?, ?::jsonb)",
        aggregateId,
        eventType,
        objectMapper.writeValueAsString(payload == null ? Map.of() : payload));
  }

  /**
   * Appends one command — a step dispatch onto the jobs exchange.
   *
   * <p>Same table, same relay, same transaction. What differs from an event is only that a command
   * names its exchange and carries the {@code messageId} its consumer dedups on; carrying the id is
   * what keeps a redelivered dispatch from running the step twice.
   *
   * @param aggregateId what the command is about — the run id
   * @param exchange the exchange to publish on, e.g. {@code orazaka.jobs}
   * @param routingKey the capability's routing key, e.g. {@code job.media.generate}
   * @param messageId the AMQP message id the consumer dedups on — the job id
   * @param payload the command body
   */
  public void appendCommand(
      String aggregateId,
      String exchange,
      String routingKey,
      String messageId,
      Map<String, Object> payload) {
    jdbcTemplate.update(
        "INSERT INTO studio_outbox (aggregate_id, event_type, exchange, message_id, payload)"
            + " VALUES (?, ?, ?, ?, ?::jsonb)",
        aggregateId,
        routingKey,
        exchange,
        messageId,
        objectMapper.writeValueAsString(payload == null ? Map.of() : payload));
  }
}
