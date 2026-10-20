package com.krizaka.orazaka.studioservice.infrastructure.adapter.persistence;

import com.krizaka.orazaka.studioservice.domain.model.WebhookDeliveryView;
import com.krizaka.orazaka.studioservice.domain.model.WebhookEndpoint;
import com.krizaka.orazaka.studioservice.domain.port.WebhookEndpointRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Webhook endpoints on {@code studio_webhook_endpoint}; every statement carries the actor. */
@Component
class JdbcWebhookEndpointRepositoryAdapter implements WebhookEndpointRepository {

  private final JdbcTemplate jdbcTemplate;

  JdbcWebhookEndpointRepositoryAdapter(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
  }

  @Override
  public int count(String actorId) {
    Integer n =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM studio_webhook_endpoint WHERE actor_id = ?",
            Integer.class,
            actorId);
    return n == null ? 0 : n;
  }

  @Override
  public WebhookEndpoint create(String actorId, String url, List<String> events, String secret) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO studio_webhook_endpoint (actor_id, url, secret, events)"
            + " VALUES (?, ?, ?, ?) RETURNING id, url, events, enabled, created_at",
        (rs, rowNum) -> endpoint(rs),
        actorId,
        url,
        secret,
        String.join(",", events));
  }

  @Override
  public List<WebhookEndpoint> list(String actorId) {
    return jdbcTemplate.query(
        "SELECT id, url, events, enabled, created_at FROM studio_webhook_endpoint"
            + " WHERE actor_id = ? ORDER BY created_at, id",
        (rs, rowNum) -> endpoint(rs),
        actorId);
  }

  @Override
  public Optional<WebhookEndpoint> find(String actorId, UUID id) {
    return jdbcTemplate
        .query(
            "SELECT id, url, events, enabled, created_at FROM studio_webhook_endpoint"
                + " WHERE actor_id = ? AND id = ?",
            (rs, rowNum) -> endpoint(rs),
            actorId,
            id)
        .stream()
        .findFirst();
  }

  @Override
  public boolean delete(String actorId, UUID id) {
    return jdbcTemplate.update(
            "DELETE FROM studio_webhook_endpoint WHERE actor_id = ? AND id = ?", actorId, id)
        > 0;
  }

  @Override
  public boolean rotate(String actorId, UUID id, String secret, Duration grace) {
    return jdbcTemplate.update(
            "UPDATE studio_webhook_endpoint SET previous_secret = secret,"
                + " previous_secret_until = now() + make_interval(secs => ?), secret = ?"
                + " WHERE actor_id = ? AND id = ?",
            grace.toSeconds(),
            secret,
            actorId,
            id)
        > 0;
  }

  @Override
  public List<WebhookDeliveryView> deliveries(String actorId, UUID id, int limit) {
    return jdbcTemplate.query(
        "SELECT d.id, d.run_id, d.event, d.status, d.attempts, d.last_http_status,"
            + " d.last_error, d.created_at, d.delivered_at FROM studio_webhook_delivery d"
            + " JOIN studio_webhook_endpoint e ON e.id = d.endpoint_id"
            + " WHERE e.actor_id = ? AND e.id = ? ORDER BY d.created_at DESC, d.id LIMIT ?",
        (rs, rowNum) -> {
          int httpStatus = rs.getInt("last_http_status");
          boolean noStatus = rs.wasNull();
          OffsetDateTime delivered = rs.getObject("delivered_at", OffsetDateTime.class);
          return new WebhookDeliveryView(
              rs.getObject("id", UUID.class),
              rs.getObject("run_id", UUID.class),
              rs.getString("event"),
              rs.getString("status"),
              rs.getInt("attempts"),
              noStatus ? null : httpStatus,
              rs.getString("last_error"),
              rs.getObject("created_at", OffsetDateTime.class).toInstant(),
              delivered == null ? null : delivered.toInstant());
        },
        actorId,
        id,
        limit);
  }

  private static WebhookEndpoint endpoint(ResultSet rs) throws SQLException {
    return new WebhookEndpoint(
        rs.getObject("id", UUID.class),
        rs.getString("url"),
        Arrays.asList(rs.getString("events").split(",")),
        rs.getBoolean("enabled"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
