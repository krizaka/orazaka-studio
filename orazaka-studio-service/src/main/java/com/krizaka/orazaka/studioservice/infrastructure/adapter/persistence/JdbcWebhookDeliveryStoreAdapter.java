package com.krizaka.orazaka.studioservice.infrastructure.adapter.persistence;

import com.krizaka.orazaka.studioservice.domain.model.DueWebhook;
import com.krizaka.orazaka.studioservice.domain.port.WebhookDeliveryStore;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Webhook deliveries on {@code studio_webhook_delivery} (infra/initdb/80-studio.sql).
 *
 * <p>Package-private adapter behind {@link WebhookDeliveryStore}. Queueing leans on the unique
 * (endpoint, run, event) constraint and claiming on {@code FOR UPDATE SKIP LOCKED} with a lease, so
 * any number of instances can sweep the same table.
 */
@Component
class JdbcWebhookDeliveryStoreAdapter implements WebhookDeliveryStore {

  /** Finished runs older than this are not queued: a sweep that long missing is an outage. */
  private static final String RECENT = "interval '2 days'";

  private final JdbcTemplate jdbcTemplate;

  JdbcWebhookDeliveryStoreAdapter(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
  }

  @Override
  public int enqueueFinishedRuns() {
    return jdbcTemplate.update(
        "INSERT INTO studio_webhook_delivery (endpoint_id, run_id, event, payload)"
            + " SELECT e.id, r.id, ev.name, jsonb_build_object('type', ev.name,"
            + "   'timestamp', to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'),"
            + "   'data', jsonb_build_object('runId', r.id, 'studioKey', r.studio_key,"
            + "     'status', r.status, 'finishedAt', r.finished_at,"
            + "     'failureCause', r.failure_cause))::text"
            + " FROM studio_run r"
            + " JOIN studio_webhook_endpoint e ON e.actor_id = r.actor_id AND e.enabled"
            + " CROSS JOIN LATERAL (SELECT CASE r.status"
            + "   WHEN 'SUCCEEDED' THEN 'run.completed' WHEN 'FAILED' THEN 'run.failed'"
            + "   WHEN 'CANCELLED' THEN 'run.cancelled' END AS name) ev"
            + " WHERE r.finished_at IS NOT NULL AND r.finished_at >= e.created_at"
            + " AND r.finished_at > now() - "
            + RECENT
            + " AND ev.name IS NOT NULL"
            + " AND (',' || e.events || ',') LIKE '%,' || ev.name || ',%'"
            + " ON CONFLICT (endpoint_id, run_id, event) DO NOTHING");
  }

  @Override
  public List<DueWebhook> claimDue(int limit, Duration lease) {
    return jdbcTemplate.query(
        "WITH claimed AS (UPDATE studio_webhook_delivery SET"
            + "   next_attempt_at = now() + make_interval(secs => ?)"
            + " WHERE id IN (SELECT d.id FROM studio_webhook_delivery d"
            + "   JOIN studio_webhook_endpoint e ON e.id = d.endpoint_id AND e.enabled"
            + "   WHERE d.status = 'PENDING' AND d.next_attempt_at <= now()"
            + "   ORDER BY d.next_attempt_at LIMIT ? FOR UPDATE OF d SKIP LOCKED)"
            + " RETURNING id, endpoint_id, event, payload, attempts)"
            + " SELECT c.id, e.url, c.event, c.payload, c.attempts, e.secret,"
            + "   CASE WHEN e.previous_secret IS NOT NULL AND e.previous_secret_until > now()"
            + "     THEN e.previous_secret END AS previous_secret"
            + " FROM claimed c JOIN studio_webhook_endpoint e ON e.id = c.endpoint_id",
        (rs, rowNum) ->
            new DueWebhook(
                rs.getObject("id", UUID.class),
                rs.getString("url"),
                rs.getString("event"),
                rs.getString("payload"),
                rs.getInt("attempts"),
                rs.getString("secret"),
                rs.getString("previous_secret")),
        lease.toSeconds(),
        limit);
  }

  @Override
  public void delivered(UUID id, int httpStatus) {
    jdbcTemplate.update(
        "UPDATE studio_webhook_delivery SET status = 'DELIVERED', attempts = attempts + 1,"
            + " last_http_status = ?, last_error = NULL, delivered_at = now() WHERE id = ?",
        httpStatus,
        id);
  }

  @Override
  public void retryLater(UUID id, Integer httpStatus, String error, Duration retryIn) {
    jdbcTemplate.update(
        "UPDATE studio_webhook_delivery SET attempts = attempts + 1, last_http_status = ?,"
            + " last_error = ?, next_attempt_at = now() + make_interval(secs => ?) WHERE id = ?",
        httpStatus,
        error,
        retryIn.toSeconds(),
        id);
  }

  @Override
  public void dead(UUID id, Integer httpStatus, String error) {
    jdbcTemplate.update(
        "UPDATE studio_webhook_delivery SET status = 'DEAD', attempts = attempts + 1,"
            + " last_http_status = ?, last_error = ? WHERE id = ?",
        httpStatus,
        error,
        id);
  }
}
