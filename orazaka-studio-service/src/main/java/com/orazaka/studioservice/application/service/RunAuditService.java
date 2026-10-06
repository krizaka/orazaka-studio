package com.orazaka.studioservice.application.service;

import com.orazaka.jobs.domain.model.DataClass;
import com.orazaka.jobs.domain.model.FailureCause;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The append-only trail a SENSITIVE run leaves behind (ADR-051, PACK_CATALOGUE §6.2).
 *
 * <p>Writes to {@code studio_run_audit}, which carries the same {@code BEFORE UPDATE OR DELETE}
 * trigger as {@code credit_ledger_entry} and for the same reason: a row somebody can edit is not an
 * audit row, and a {@code REVOKE} would not achieve it because the table owner keeps implicit
 * rights on its own table.
 *
 * <p><b>It records that a run happened and how it ended — never what it matched, never its
 * subject</b> (ADR-065). Run, actor, pack, class, event, and the failure's category. This class
 * said "at most a reason code" and took a free-text {@code detail}; the saga filled it with the
 * failed step's message, which for a guard refusal is the pack's reviewed answer. On a REGULATED
 * pack that answer is the crisis response, so the append-only trail — the one table retention can
 * never touch — kept a permanent record that a crisis response was returned to this person. The
 * column is gone: a sentence is no longer something this trail can hold. {@code GUARD_REFUSAL}
 * still says a run was protected rather than broken, which is what ADR-053 keeps the trail for; it
 * does not say which guard.
 *
 * <p>A STANDARD run writes nothing. Auditing every run of every pack would turn the trail into a
 * log, and a log is the thing nobody reads.
 */
@Service
public class RunAuditService {

  private static final Logger logger = LoggerFactory.getLogger(RunAuditService.class);

  private static final String INSERT =
      """
      INSERT INTO studio_run_audit
        (run_id, actor_id, pack_key, studio_key, data_class, event, step_id, failure_cause)
      SELECT ?, ?, COALESCE(ps.pack_key, '<none>'), ?, ?, ?, ?, ?
        FROM (SELECT 1) one
        LEFT JOIN pack_studio ps ON ps.studio_key = ?
      """;

  private final JdbcTemplate jdbcTemplate;

  public RunAuditService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
  }

  /**
   * Records that a protected run began.
   *
   * @param runId the run
   * @param actorId the opaque actor
   * @param studioKey the Studio
   * @param dataClass the class stamped on the run
   */
  public void recordStarted(UUID runId, String actorId, String studioKey, DataClass dataClass) {
    record(runId, actorId, studioKey, dataClass, "RUN_STARTED", null, null);
  }

  /**
   * Records a protected run's terminal outcome.
   *
   * @param runId the run
   * @param actorId the opaque actor
   * @param studioKey the Studio
   * @param dataClass the class stamped on the run
   * @param succeeded whether it finished successfully
   * @param cause why it failed, in the closed vocabulary of {@link FailureCause}; {@code null} on
   *     success. <b>The category, not the sentence</b>: the whole reason a protected pack keeps a
   *     trail is to be able to show that a refused run was <i>protected</i> and not <i>broken</i>,
   *     and until ADR-053 both were the same row with different prose in it
   */
  public void recordFinished(
      UUID runId, String actorId, String studioKey, boolean succeeded, FailureCause cause) {
    // The class is read back from the run rather than passed in: the run row is the authority on
    // what class its material carries, and a caller re-deriving it could disagree with the row the
    // sweeper and the analytics filter are both reading (ADR-051).
    record(
        runId,
        actorId,
        studioKey,
        dataClassOf(runId),
        succeeded ? "RUN_SUCCEEDED" : "RUN_FAILED",
        null,
        succeeded ? null : (cause == null ? FailureCause.EXECUTOR_FAULT : cause).name());
  }

  /** What the run itself says its class is; STANDARD when the run is already gone. */
  private DataClass dataClassOf(UUID runId) {
    return jdbcTemplate
        .query(
            "SELECT data_class FROM studio_run WHERE id = ?",
            (rs, rowNum) -> DataClass.valueOf(rs.getString(1)),
            runId)
        .stream()
        .findFirst()
        .orElse(DataClass.STANDARD);
  }

  private void record(
      UUID runId,
      String actorId,
      String studioKey,
      DataClass dataClass,
      String event,
      String stepId,
      String failureCause) {
    if (dataClass == null || !dataClass.isProtected()) {
      return;
    }
    try {
      jdbcTemplate.update(
          INSERT,
          runId,
          actorId,
          studioKey,
          dataClass.name(),
          event,
          stepId,
          failureCause,
          studioKey);
    } catch (RuntimeException e) {
      // Never propagate: a run that completed must not be reported as failed because its trail
      // could not be written. The failure is loud in the log, which is where an operator finds a
      // trail that stopped — the same contract CreditReservationService settles under.
      logger.error(
          "Could not write the audit trail for run {} ({}): the run is unaffected",
          runId,
          event,
          e);
    }
  }
}
