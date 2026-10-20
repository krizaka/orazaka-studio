package com.krizaka.orazaka.studioservice.infrastructure.adapter.persistence;

import com.krizaka.orazaka.studioservice.domain.port.RunEventLog;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads {@code studio_run.event_seq}, which the trigger of infra/initdb/80-studio.sql raises at
 * each change of a run or of one of its steps.
 */
@Component
class JdbcRunEventLogAdapter implements RunEventLog {

  private final JdbcTemplate jdbcTemplate;

  JdbcRunEventLogAdapter(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
  }

  @Override
  public OptionalLong currentSeq(UUID runId, String actorId) {
    return jdbcTemplate
        .query(
            "SELECT event_seq FROM studio_run WHERE id = ? AND actor_id = ?",
            (rs, rowNum) -> rs.getLong(1),
            runId,
            actorId)
        .stream()
        .mapToLong(Long::longValue)
        .findFirst();
  }
}
