package com.krizaka.orazaka.studioservice.application.service;

import com.krizaka.orazaka.jobs.domain.model.DataClass;
import com.krizaka.orazaka.studio.domain.model.PackConsent;
import com.krizaka.orazaka.studio.domain.model.RegulatoryClass;
import com.krizaka.orazaka.studioservice.domain.exception.PackNotFoundException;
import com.krizaka.orazaka.studioservice.domain.exception.RunNotFoundException;
import com.krizaka.orazaka.studioservice.domain.model.ConsentNotice;
import com.krizaka.orazaka.studioservice.domain.model.ErasureReceipt;
import com.krizaka.orazaka.studioservice.domain.policy.CrisisResources;
import com.krizaka.orazaka.studioservice.domain.port.JobErasureClient;
import com.krizaka.orazaka.studioservice.infrastructure.support.ColumnValueResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a person reads before agreeing to a regulated pack, and their right to take it back: erase
 * everything the pack holds of them, or one entry (orazaka#301 - GDPR art. 17, Loi 25).
 *
 * <p>The consent statement promises « you can delete everything at any time ». This is what makes
 * the sentence true, so it deletes <b>for real</b>: the runs (inputs and outputs, steps, events),
 * the webhook deliveries and outbox messages that were built from them, the installations with the
 * consent they recorded, and - through {@link JobErasureClient} - the job plane's rows and the
 * files they wrote, on disk and in the durable store. Nothing is soft-revoked.
 *
 * <ul>
 *   <li><b>Owner only.</b> Every statement is scoped by the caller's actor id, as everywhere in
 *       this service; a run that is somebody else's is indistinguishable from one that is gone.
 *   <li><b>Idempotent.</b> Asking again erases nothing more, answers the same way and writes no new
 *       audit row.
 *   <li><b>Audited without content.</b> One row of the append-only trail per installation erased
 *       ({@code DATA_ERASED}), or per entry ({@code RUN_ERASED}): who, which Studio, which class,
 *       when. That row is the minimal proof of withdrawal, kept because an erasure nobody can show
 *       was done is half an answer to a regulator; it holds no word of what was erased. The audit
 *       write is not best-effort: if it fails the erasure rolls back and is asked again.
 *   <li><b>All or nothing across services.</b> The job plane is asked first, inside the
 *       transaction; if it cannot erase everything, nothing here is deleted and the caller is told
 *       to ask again - never that the data is gone when part of it is not.
 * </ul>
 */
@Service
public class ProtectedDataService {

  private static final Logger logger = LoggerFactory.getLogger(ProtectedDataService.class);

  private static final String RETENTION_KEY = "retention.sensitive-run-days";
  private static final int RETENTION_FALLBACK_DAYS = 30;

  private final JdbcTemplate jdbcTemplate;
  private final ColumnValueResolver columns;
  private final StudioRuntimeConfigService runtimeConfig;
  private final StudioRunService runService;
  private final RunAuditService auditService;
  private final JobErasureClient jobErasure;

  public ProtectedDataService(
      JdbcTemplate jdbcTemplate,
      ColumnValueResolver columns,
      StudioRuntimeConfigService runtimeConfig,
      StudioRunService runService,
      RunAuditService auditService,
      JobErasureClient jobErasure) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
    this.columns = Objects.requireNonNull(columns, "ColumnValueResolver required");
    this.runtimeConfig = Objects.requireNonNull(runtimeConfig, "config required");
    this.runService = Objects.requireNonNull(runService, "StudioRunService required");
    this.auditService = Objects.requireNonNull(auditService, "RunAuditService required");
    this.jobErasure = Objects.requireNonNull(jobErasure, "JobErasureClient required");
  }

  /**
   * The consent notice of a regulated pack, in the caller's language.
   *
   * @param packKey the pack
   * @param locale the caller's language tag; an unknown language gets the default statement
   * @return the notice
   * @throws PackNotFoundException when the key names no pack that asks for consent
   */
  @Transactional(readOnly = true)
  public ConsentNotice consentNotice(String packKey, String locale) {
    return jdbcTemplate
        .query(
            "SELECT consent_version, consent_statement, consent_statements, safety FROM pack"
                + " WHERE pack_key = ? AND consent_version IS NOT NULL",
            (rs, rowNum) -> {
              PackConsent consent =
                  new PackConsent(
                      rs.getString("consent_version"),
                      rs.getString("consent_statement"),
                      columns.stringMap(rs.getString("consent_statements")));
              return new ConsentNotice(
                  packKey,
                  consent.version(),
                  consent.statementFor(locale),
                  consent.allStatements(),
                  CrisisResources.of(columns.objectMap(rs.getString("safety"))),
                  runtimeConfig.intValue(RETENTION_KEY, RETENTION_FALLBACK_DAYS));
            },
            packKey)
        .stream()
        .findFirst()
        .orElseThrow(() -> new PackNotFoundException(packKey));
  }

  /**
   * Erases everything a pack holds of one person.
   *
   * @param actorId the person asking, from the verified token - never from the request
   * @param packKey the pack
   * @return counts of what was removed; zeros when there was nothing, which is also the answer to
   *     asking twice
   * @throws PackNotFoundException when the key names no pack, or a pack that holds no protected
   *     data
   * @throws com.krizaka.orazaka.studioservice.domain.exception.ErasureIncompleteException when the
   *     job plane could not erase its part - nothing was deleted, ask again
   */
  @Transactional
  public ErasureReceipt eraseAll(String actorId, String packKey) {
    DataClass dataClass = protectedClassOf(packKey);

    List<Installation> installations =
        jdbcTemplate.query(
            """
            SELECT i.id, i.studio_key
              FROM studio_installation i JOIN pack_studio ps ON ps.studio_key = i.studio_key
             WHERE i.actor_id = ? AND ps.pack_key = ?
            """,
            (rs, rowNum) -> new Installation(rs.getObject(1, UUID.class), rs.getString(2)),
            actorId,
            packKey);
    List<RunRef> runs =
        jdbcTemplate.query(
            """
            SELECT r.id, r.finished_at IS NULL
              FROM studio_run r JOIN pack_studio ps ON ps.studio_key = r.studio_key
             WHERE r.actor_id = ? AND ps.pack_key = ?
            """,
            (rs, rowNum) -> new RunRef(rs.getObject(1, UUID.class), rs.getBoolean(2)),
            actorId,
            packKey);

    int jobs = eraseRuns(actorId, runs);
    int removedInstallations = 0;
    if (!installations.isEmpty()) {
      removedInstallations =
          jdbcTemplate.update(
              "DELETE FROM studio_installation WHERE actor_id = ? AND id IN ("
                  + placeholders(installations.size())
                  + ")",
              args(actorId, installations.stream().map(Installation::id).toList()));
    }
    for (Installation installation : installations) {
      auditService.recordErased(
          installation.id(), actorId, installation.studioKey(), dataClass, true);
    }
    if (!runs.isEmpty() || removedInstallations > 0) {
      // Counts only: what was erased is never logged, and neither is who asked.
      logger.info(
          "Erased pack {}: {} entries, {} installations, {} jobs",
          packKey,
          runs.size(),
          removedInstallations,
          jobs);
    }
    return new ErasureReceipt(packKey, runs.size(), removedInstallations, jobs);
  }

  /**
   * Erases one entry - one run of a protected pack, with its inputs, outputs and jobs. The
   * installation and its consent stay: the person has not left, they removed one entry.
   *
   * <p>Idempotent and silent about ownership: a run that is already gone, never existed, is not the
   * caller's or belongs to a pack that holds no protected data all answer the same - nothing to
   * erase.
   *
   * @param actorId the person asking
   * @param runId the entry
   * @return whether an entry was erased by this call
   * @throws com.krizaka.orazaka.studioservice.domain.exception.ErasureIncompleteException when the
   *     job plane could not erase its part - nothing was deleted, ask again
   */
  @Transactional
  public boolean eraseEntry(String actorId, UUID runId) {
    List<RunRef> runs =
        jdbcTemplate.query(
            "SELECT id, finished_at IS NULL, studio_key, data_class FROM studio_run"
                + " WHERE id = ? AND actor_id = ? AND data_class <> 'STANDARD'",
            (rs, rowNum) ->
                new RunRef(
                    rs.getObject(1, UUID.class),
                    rs.getBoolean(2),
                    rs.getString(3),
                    DataClass.valueOf(rs.getString(4))),
            runId,
            actorId);
    if (runs.isEmpty()) {
      return false;
    }
    RunRef run = runs.getFirst();
    eraseRuns(actorId, runs);
    auditService.recordErased(run.id(), actorId, run.studioKey(), run.dataClass(), false);
    return true;
  }

  /**
   * Deletes runs and everything built from them, the job plane first.
   *
   * @return how many jobs the job plane erased
   */
  private int eraseRuns(String actorId, List<RunRef> runs) {
    if (runs.isEmpty()) {
      return 0;
    }
    List<UUID> ids = runs.stream().map(RunRef::id).toList();
    // A run still going is stopped first (its hold released, its jobs told to stop) and then
    // erased; the stop commands carry a job id and nothing else, and are kept below.
    for (RunRef run : runs) {
      if (run.active()) {
        try {
          runService.cancel(run.id(), actorId);
        } catch (RunNotFoundException ignored) {
          // Gone between the listing and now: there is nothing left to stop.
        }
      }
    }
    List<String> jobIds =
        jdbcTemplate.query(
            "SELECT DISTINCT job_id FROM studio_run_step WHERE job_id IS NOT NULL AND run_id IN ("
                + placeholders(ids.size())
                + ")",
            (rs, rowNum) -> rs.getString(1),
            ids.toArray());
    // First, and able to stop everything: if the plane that holds the files cannot erase them,
    // nothing of this service's is deleted either.
    int jobs = jobIds.isEmpty() ? 0 : jobErasure.erase(actorId, jobIds);

    String in = placeholders(ids.size());
    Object[] runArgs = ids.toArray();
    jdbcTemplate.update(
        "DELETE FROM studio_webhook_delivery WHERE run_id IN (" + in + ")", runArgs);
    // The messages built from a run: its dispatch commands carry the person's words. A stop
    // command (job.cancel.*) carries a job id only and must still reach the executor.
    jdbcTemplate.update(
        "DELETE FROM studio_outbox WHERE event_type NOT LIKE 'job.cancel.%' AND aggregate_id IN ("
            + in
            + ")",
        ids.stream().map(UUID::toString).toArray());
    // Steps and events go with the run (ON DELETE CASCADE).
    jdbcTemplate.update(
        "DELETE FROM studio_run WHERE actor_id = ? AND id IN (" + in + ")", args(actorId, ids));
    return jobs;
  }

  private DataClass protectedClassOf(String packKey) {
    String regulatoryClass =
        jdbcTemplate
            .query(
                "SELECT regulatory_class FROM pack WHERE pack_key = ?",
                (rs, rowNum) -> rs.getString(1),
                packKey)
            .stream()
            .findFirst()
            .orElseThrow(() -> new PackNotFoundException(packKey));
    DataClass dataClass = RegulatoryClass.valueOf(regulatoryClass).dataClass();
    if (!dataClass.isProtected()) {
      // A pack that holds no protected data has nothing this endpoint is for.
      throw new PackNotFoundException(packKey);
    }
    return dataClass;
  }

  private static String placeholders(int count) {
    return String.join(",", java.util.Collections.nCopies(count, "?"));
  }

  /** The actor first, then the ids. */
  private static Object[] args(String actorId, List<UUID> ids) {
    List<Object> args = new ArrayList<>();
    args.add(actorId);
    args.addAll(ids);
    return args.toArray();
  }

  private record Installation(UUID id, String studioKey) {}

  private record RunRef(UUID id, boolean active, String studioKey, DataClass dataClass) {
    RunRef(UUID id, boolean active) {
      this(id, active, null, null);
    }
  }
}
