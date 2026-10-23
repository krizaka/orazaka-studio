package com.krizaka.orazaka.studioservice.infrastructure.adapter.persistence;

import com.krizaka.orazaka.studio.domain.model.RunStatus;
import com.krizaka.orazaka.studioservice.domain.port.RunResultCache;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Finds a recorded result on {@code studio_run} (infra/initdb/80-studio.sql, index {@code
 * idx_studio_run_result_cache}). Only an original run is a source: a cached answer is never copied
 * again, so every answer names the run that really computed it.
 */
@Component
class JdbcRunResultCacheAdapter implements RunResultCache {

  private final JdbcTemplate jdbcTemplate;

  JdbcRunResultCacheAdapter(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
  }

  @Override
  public Optional<UUID> findSucceeded(
      String actorId, String studioKey, String version, String fingerprint) {
    return jdbcTemplate
        .query(
            "SELECT id FROM studio_run WHERE actor_id = ? AND studio_key = ? AND blueprint_version = ?"
                + " AND input_fingerprint = ? AND status = ? AND cached_from IS NULL"
                + " AND outputs IS NOT NULL ORDER BY finished_at DESC LIMIT 1",
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            actorId,
            studioKey,
            version,
            fingerprint,
            RunStatus.SUCCEEDED.name())
        .stream()
        .findFirst();
  }
}
