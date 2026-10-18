package com.krizaka.orazaka.studioservice.infrastructure.adapter.persistence;

import com.krizaka.orazaka.studioservice.domain.port.RunIdempotencyStore;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

/**
 * Keeps idempotency keys on {@code studio_run.idempotency_key} (infra/initdb/80-studio.sql).
 *
 * <p>Package-private adapter behind {@link RunIdempotencyStore}. Two guards, as in the ledger
 * (ADR-033): a transaction-scoped advisory lock makes two parallel requests with one key take
 * turns, and the partial unique index {@code ux_studio_run_idempotency} is the backstop that no
 * code path can bypass.
 */
@Component
class JdbcRunIdempotencyStoreAdapter implements RunIdempotencyStore {

  private final JdbcTemplate jdbcTemplate;

  JdbcRunIdempotencyStoreAdapter(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
  }

  @Override
  public void lock(String actorId, String key) {
    jdbcTemplate.query(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
        (RowCallbackHandler) rs -> {},
        "studio-run-idempotency:" + actorId.length() + ":" + actorId + key);
  }

  @Override
  public Optional<Recorded> find(String actorId, String key) {
    return jdbcTemplate
        .query(
            "SELECT id, request_fingerprint FROM studio_run"
                + " WHERE actor_id = ? AND idempotency_key = ?",
            (rs, rowNum) ->
                new Recorded(rs.getObject("id", UUID.class), rs.getString("request_fingerprint")),
            actorId,
            key)
        .stream()
        .findFirst();
  }

  @Override
  public void record(UUID runId, String actorId, String key, String fingerprint) {
    jdbcTemplate.update(
        "UPDATE studio_run SET idempotency_key = ?, request_fingerprint = ?"
            + " WHERE id = ? AND actor_id = ?",
        key,
        fingerprint,
        runId,
        actorId);
  }
}
