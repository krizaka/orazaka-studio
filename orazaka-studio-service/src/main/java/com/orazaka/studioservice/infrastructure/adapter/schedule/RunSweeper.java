package com.orazaka.studioservice.infrastructure.adapter.schedule;

import com.orazaka.jobs.domain.model.FailureCause;
import com.orazaka.studio.domain.model.RunStatus;
import com.orazaka.studio.domain.model.RunStepStatus;
import com.orazaka.studioservice.application.service.RunAuditService;
import com.orazaka.studioservice.application.service.RunSettlementService;
import com.orazaka.studioservice.application.service.StudioRuntimeConfigService;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Closes runs whose steps stopped reporting, and releases their holds.
 *
 * <p>ADR-034 names this the one thing that hurts if skipped. A crashed worker leaves a step {@code
 * RUNNING} forever; without this, the run never reaches a terminal state and its hold silently
 * freezes a paying actor's balance — a failure that appears in no happy-path test and that users
 * experience as "my credits vanished".
 *
 * <p>The timeout is a row, not a constant: the number that needs changing when the accelerator is
 * saturated must be changeable without a deploy.
 *
 * <p><b>One ceiling per lane</b> (ADR-067), because {@code started_at} is stamped when the step row
 * is INSERTED — at dispatch, before any worker takes the message — so <b>queue wait counts against
 * the deadline</b>. A single number had to be large enough not to reap a 67-second image that
 * queued and small enough to catch a 200 ms text step that hung, and it cannot be both. Today the
 * contradiction is invisible because there is no contention; once the queue genuinely fills, a
 * legitimately-queued image gets reaped — run FAILED, hold released, and the worker still burning
 * the accelerator for a run that is already dead. That is a correctness consequence, not a latency
 * one, which is why it is closed before M3 makes this the only path.
 */
@Component
public class RunSweeper {

  private static final Logger logger = LoggerFactory.getLogger(RunSweeper.class);

  private static final String INTERACTIVE_TIMEOUT_KEY = "run.step-timeout-seconds.interactive";
  private static final int INTERACTIVE_FALLBACK_SECONDS = 300;

  private static final String BATCH_TIMEOUT_KEY = "run.step-timeout-seconds.batch";
  private static final int BATCH_FALLBACK_SECONDS = 1800;

  /** The lane a step that never declared one is judged by — the one that waits (ADR-067). */
  private static final String DEFAULT_LANE = "BATCH";

  private final JdbcTemplate jdbcTemplate;
  private final RunSettlementService settlementService;
  private final StudioRuntimeConfigService runtimeConfigService;

  private final RunAuditService runAuditService;

  public RunSweeper(
      JdbcTemplate jdbcTemplate,
      RunSettlementService settlementService,
      StudioRuntimeConfigService runtimeConfigService,
      RunAuditService runAuditService) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
    this.settlementService = Objects.requireNonNull(settlementService, "settlement");
    this.runtimeConfigService = Objects.requireNonNull(runtimeConfigService, "config required");
    this.runAuditService = Objects.requireNonNull(runAuditService, "RunAuditService required");
  }

  /** Fails runs whose steps exceeded the timeout, releasing every outstanding hold. */
  @Scheduled(fixedDelayString = "${orazaka.studio-service.sweeper.interval:60000}")
  @Transactional
  public void sweep() {
    int interactiveSeconds =
        runtimeConfigService.intValue(INTERACTIVE_TIMEOUT_KEY, INTERACTIVE_FALLBACK_SECONDS);
    int batchSeconds = runtimeConfigService.intValue(BATCH_TIMEOUT_KEY, BATCH_FALLBACK_SECONDS);

    List<StaleRun> stale =
        jdbcTemplate.query(
            "SELECT DISTINCT r.id, r.hold_id, r.actor_id, r.studio_key,"
                + " COALESCE(s.latency_class, ?) AS lane FROM studio_run r"
                + " JOIN studio_run_step s ON s.run_id = r.id"
                + " WHERE r.finished_at IS NULL AND s.status = ?"
                + " AND s.started_at < now() - make_interval(secs => CASE"
                + "   WHEN COALESCE(s.latency_class, ?) = 'INTERACTIVE' THEN ? ELSE ? END)",
            (rs, rowNum) ->
                new StaleRun(
                    rs.getObject("id", UUID.class),
                    rs.getString("hold_id"),
                    rs.getString("actor_id"),
                    rs.getString("studio_key"),
                    rs.getString("lane")),
            DEFAULT_LANE,
            RunStepStatus.RUNNING.name(),
            DEFAULT_LANE,
            interactiveSeconds,
            batchSeconds);

    for (StaleRun run : stale) {
      int ceiling = "INTERACTIVE".equals(run.lane()) ? interactiveSeconds : batchSeconds;
      logger.warn(
          "Run {} has a {} step past the {}s ceiling — failing it and releasing its hold",
          run.id(),
          run.lane(),
          ceiling);

      String reason =
          "a " + run.lane() + " step exceeded the " + ceiling + "s ceiling and never reported back";
      // The sweeper is the fourth producer of a failed run, and under ADR-053 it declares its cause
      // like the other three. A step that stopped reporting is TIMEOUT by definition: the platform
      // lost track of work it had accepted, which releases the hold and blames nobody.
      jdbcTemplate.update(
          "UPDATE studio_run SET status = ?, error_message = ?, failure_cause = ?,"
              + " finished_at = now() WHERE id = ? AND finished_at IS NULL",
          RunStatus.FAILED.name(),
          reason,
          FailureCause.TIMEOUT.name(),
          run.id());
      jdbcTemplate.update(
          "UPDATE studio_run_step SET status = ?, finished_at = now()"
              + " WHERE run_id = ? AND status IN (?, ?)",
          RunStepStatus.FAILED.name(),
          run.id(),
          RunStepStatus.PENDING.name(),
          RunStepStatus.RUNNING.name());

      // The whole point: a stranded run must not strand its credits with it.
      settlementService.releaseAbandoned(run.holdId(), "swept: step exceeded its ceiling");
      // And a protected run that ended this way must appear in its own trail. Before ADR-053 a
      // swept SENSITIVE run left no audit row at all — the one terminal path that recorded
      // nothing, which is the hole a trail cannot have.
      runAuditService.recordFinished(
          run.id(), run.actorId(), run.studioKey(), false, FailureCause.TIMEOUT);
    }
  }

  /** A run with a step that stopped reporting, and the lane whose ceiling it passed. */
  private record StaleRun(UUID id, String holdId, String actorId, String studioKey, String lane) {}
}
