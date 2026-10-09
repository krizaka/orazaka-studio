package com.orazaka.studioservice.application.service;

import com.krizaka.messaging.outbox.OutboxMessage;
import com.krizaka.messaging.outbox.OutboxStore;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
 *
 * <p>It is also the studio context's {@link OutboxStore}: the krizaka-messaging relay drains {@code
 * studio_outbox} through it. The claim is {@code FOR UPDATE SKIP LOCKED} — the relay this replaced
 * selected pending rows with no lock at all, which publishes a row twice the day a second instance
 * runs (ADR-058 §3).
 */
@Service
public class OutboxService implements OutboxStore {

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

  /**
   * Claims the due rows, oldest first. {@code event_type} is the routing key.
   *
   * @param batchSize the most rows to claim
   * @return the claimed rows, locked until the relay's transaction ends
   */
  @Override
  public List<OutboxMessage> lockPendingBatch(int batchSize) {
    return jdbcTemplate.query(
        "SELECT id, event_type, exchange, message_id, payload::text, attempts FROM studio_outbox"
            + " WHERE published_at IS NULL AND next_attempt_at <= now()"
            + " ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED",
        (rs, rowNum) ->
            new OutboxMessage(
                rs.getObject("id", UUID.class),
                rs.getString("exchange"),
                rs.getString("event_type"),
                rs.getString("message_id"),
                rs.getString("payload").getBytes(StandardCharsets.UTF_8),
                rs.getInt("attempts")),
        batchSize);
  }

  /**
   * Records that a row reached the broker.
   *
   * @param id the row
   */
  @Override
  public void markPublished(UUID id) {
    jdbcTemplate.update("UPDATE studio_outbox SET published_at = now() WHERE id = ?", id);
  }

  /**
   * Backs a row off after a failed publish: 5 s doubling per attempt, capped at 5 × 2⁶ s.
   *
   * @param id the row
   * @param previousAttempts the attempts recorded before this failure
   */
  @Override
  public void recordFailure(UUID id, int previousAttempts) {
    jdbcTemplate.update(
        "UPDATE studio_outbox SET attempts = attempts + 1,"
            + " next_attempt_at = now() + (INTERVAL '5 seconds' * POWER(2, LEAST(attempts, 6)))"
            + " WHERE id = ?",
        id);
  }

  /**
   * Deletes delivered rows older than the cutoff; undelivered rows are kept as evidence.
   *
   * @param cutoff rows published before this instant are deleted
   * @return how many rows were deleted
   */
  @Override
  public long purgePublishedBefore(Instant cutoff) {
    return jdbcTemplate.update(
        "DELETE FROM studio_outbox WHERE published_at IS NOT NULL AND published_at < ?",
        Timestamp.from(cutoff));
  }
}
