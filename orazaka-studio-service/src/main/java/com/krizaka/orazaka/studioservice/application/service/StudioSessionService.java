package com.krizaka.orazaka.studioservice.application.service;

import static com.krizaka.orazaka.studioservice.application.service.RunSagaService.CLOSED_BY;
import static com.krizaka.orazaka.studioservice.application.service.RunSagaService.CLOSED_BY_CLIENT;
import static com.krizaka.orazaka.studioservice.application.service.RunSagaService.CLOSED_BY_IDLE;
import static com.krizaka.orazaka.studioservice.application.service.RunSagaService.SESSION_LANE;

import com.krizaka.orazaka.studio.domain.model.RunStatus;
import com.krizaka.orazaka.studio.domain.model.RunStepStatus;
import com.krizaka.orazaka.studioservice.domain.exception.RunNotFoundException;
import com.krizaka.orazaka.studioservice.domain.exception.SessionClosedException;
import com.krizaka.orazaka.studioservice.domain.exception.SessionClosedException.Reason;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The life of a {@link com.krizaka.orazaka.studio.domain.model.StepKind#SESSION} step (ADR-093,
 * krizaka/orazaka#450): a run that stays RUNNING while a client sends turns.
 *
 * <p>Open: the run's start (the saga). Each turn {@link #touch}es it, which also refuses the turn
 * once the session is over. It ends on {@link #finish} (the client's « finish »), on {@link
 * #expireIdle} (no turn for {@code session.idle-ttl-seconds}, a row of the settings), or by the
 * ordinary cancel and sweep. Ended by the client or the TTL, the run SUCCEEDS and settles like any
 * other; cancelled or failed, it releases its hold. Billing is per turn, on the same run id, by the
 * engine; the pack is re-checked per turn by {@link CodeTurnService}.
 */
@Service
public class StudioSessionService {

  private static final Logger logger = LoggerFactory.getLogger(StudioSessionService.class);

  static final String IDLE_TTL_KEY = "session.idle-ttl-seconds";

  // @fixed: the fallback of a missing settings row; the value is the DB's (min 60 s).
  private static final int IDLE_TTL_FALLBACK_SECONDS = 1800;

  private final JdbcTemplate jdbcTemplate;
  private final RunSagaService saga;
  private final StudioRuntimeConfigService config;
  private final StudioRunService runs;

  /**
   * @param jdbcTemplate the Studio database
   * @param saga ends the step and settles the run
   * @param config the settings
   * @param runs the runs, scoped to their owner
   */
  public StudioSessionService(
      JdbcTemplate jdbcTemplate,
      RunSagaService saga,
      StudioRuntimeConfigService config,
      StudioRunService runs) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
    this.saga = Objects.requireNonNull(saga, "RunSagaService cannot be null");
    this.config = Objects.requireNonNull(config, "StudioRuntimeConfigService cannot be null");
    this.runs = Objects.requireNonNull(runs, "StudioRunService cannot be null");
  }

  /**
   * Marks a turn on an open session, so its idle TTL restarts. One atomic statement: a session
   * closed meanwhile (finish, cancel, sweep) touches nothing, and the turn is refused.
   *
   * @param runId the session's run
   * @throws SessionClosedException when the session is over, saying why
   */
  @Transactional
  public void touch(UUID runId) {
    int touched =
        jdbcTemplate.update(
            "UPDATE studio_run_step SET last_turn_at = now() WHERE run_id = ?"
                + " AND latency_class = ? AND status = ?",
            runId,
            SESSION_LANE,
            RunStepStatus.RUNNING.name());
    if (touched == 0) {
      throw new SessionClosedException(runId, reasonOver(runId));
    }
  }

  /**
   * The client's « finish »: closes the session and settles the run. Idempotent: finishing a
   * session that is already over answers quietly, so a retried call is harmless.
   *
   * @param runId the session's run
   * @param actorId the caller, who must own the run
   * @throws RunNotFoundException when the run is not the caller's
   */
  @Transactional
  public void finish(UUID runId, String actorId) {
    runs.find(runId, actorId).orElseThrow(() -> new RunNotFoundException(runId));
    if (isOpen(runId)) {
      saga.closeSession(runId, CLOSED_BY_CLIENT);
    }
  }

  /**
   * Closes the sessions silent for longer than the idle TTL.
   *
   * @return how many were closed
   */
  @Transactional
  public int expireIdle() {
    int ttl = Math.max(60, config.intValue(IDLE_TTL_KEY, IDLE_TTL_FALLBACK_SECONDS));
    List<UUID> idle =
        jdbcTemplate.queryForList(
            "SELECT run_id FROM studio_run_step WHERE latency_class = ? AND status = ?"
                + " AND COALESCE(last_turn_at, started_at) < now() - make_interval(secs => ?)",
            UUID.class,
            SESSION_LANE,
            RunStepStatus.RUNNING.name(),
            ttl);
    for (UUID runId : idle) {
      logger.info("Session {} was silent for {}s — closing it as expired", runId, ttl);
      saga.closeSession(runId, CLOSED_BY_IDLE);
    }
    return idle.size();
  }

  private boolean isOpen(UUID runId) {
    Integer open =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM studio_run_step WHERE run_id = ? AND latency_class = ?"
                + " AND status = ?",
            Integer.class,
            runId,
            SESSION_LANE,
            RunStepStatus.RUNNING.name());
    return open != null && open > 0;
  }

  /** Why a session takes no more turns: the run's status, and who closed a succeeded one. */
  private Reason reasonOver(UUID runId) {
    List<String> rows =
        jdbcTemplate.queryForList(
            "SELECT r.status || ':' || COALESCE(s.output ->> ?, '') FROM studio_run r"
                + " LEFT JOIN studio_run_step s ON s.run_id = r.id AND s.latency_class = ?"
                + " WHERE r.id = ?",
            String.class,
            CLOSED_BY,
            SESSION_LANE,
            runId);
    String row = rows.isEmpty() ? "" : rows.getFirst();
    if (row.startsWith(RunStatus.CANCELLED.name())) {
      return Reason.CANCELLED;
    }
    if (row.startsWith(RunStatus.FAILED.name())) {
      return Reason.FAILED;
    }
    return row.endsWith(":" + CLOSED_BY_IDLE) ? Reason.EXPIRED : Reason.FINISHED;
  }
}
