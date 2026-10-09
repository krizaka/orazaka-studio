package com.krizaka.orazaka.studioservice.infrastructure.adapter.schedule;

import com.krizaka.messaging.dedup.MessageDedup;
import com.krizaka.orazaka.studio.domain.model.InstallationStatus;
import com.krizaka.orazaka.studioservice.application.service.StudioRuntimeConfigService;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purges what the retention window says may no longer be kept (ADR-034 §14, GDPR / Loi 25).
 *
 * <p>Two obligations, deliberately separate. Finished runs age out after the configured window,
 * because a run holds the actor's own material — their photos, their brief. Revoked installations
 * are purged later still, so a reinstall inside the grace period restores the configuration rather
 * than asking the professional to fill the dialog in again.
 *
 * <p>The windows are rows, not constants: a retention period is a legal parameter that changes by
 * jurisdiction and by contract, and neither is a reason to redeploy.
 */
@Component
public class RetentionSweeper {

  private static final Logger logger = LoggerFactory.getLogger(RetentionSweeper.class);

  private static final String RUN_RETENTION_KEY = "retention.run-days";
  private static final int RUN_RETENTION_FALLBACK_DAYS = 90;

  private static final String SENSITIVE_RETENTION_KEY = "retention.sensitive-run-days";
  private static final int SENSITIVE_RETENTION_FALLBACK_DAYS = 30;

  /**
   * The installation's own window, when its owner asked for a shorter one.
   *
   * <p>Read from the installation config an actor fills at install time. The sweeper takes the
   * MINIMUM of this and the platform's window, so the setting can only ever shorten the memory — a
   * pack that set it to 3650 would otherwise have bought itself a longer one than the class allows,
   * which is the whole thing the class is for (ADR-051).
   */
  private static final String INSTALLATION_RETENTION_OVERRIDE = "retentionDays";

  private static final String INSTALLATION_RETENTION_KEY = "retention.revoked-installation-days";
  private static final int INSTALLATION_RETENTION_FALLBACK_DAYS = 30;

  /** Dedup claims outlive their usefulness the moment redelivery is impossible. */
  private static final Duration DEDUP_RETENTION = Duration.ofDays(7);

  private final JdbcTemplate jdbcTemplate;
  private final StudioRuntimeConfigService runtimeConfigService;
  private final MessageDedup messageDedup;

  public RetentionSweeper(
      JdbcTemplate jdbcTemplate,
      StudioRuntimeConfigService runtimeConfigService,
      MessageDedup messageDedup) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
    this.runtimeConfigService = Objects.requireNonNull(runtimeConfigService, "config required");
    this.messageDedup = Objects.requireNonNull(messageDedup, "MessageDedup cannot be null");
  }

  /** Deletes aged-out runs and long-revoked installations. */
  @Scheduled(cron = "${orazaka.studio-service.retention.cron:0 30 3 * * *}")
  @Transactional
  public void purge() {
    int runDays = runtimeConfigService.intValue(RUN_RETENTION_KEY, RUN_RETENTION_FALLBACK_DAYS);
    // studio_run_step cascades on the run, so one delete closes both tables.
    int runs =
        jdbcTemplate.update(
            "DELETE FROM studio_run WHERE data_class = 'STANDARD' AND finished_at IS NOT NULL"
                + " AND finished_at < now() - make_interval(days => ?)",
            runDays);

    // Runs of a protected pack age out on their own, shorter window — and on the installation's
    // if its owner chose a shorter one still. GREATEST(1, …) because a window of zero would delete
    // a run that is still being read from the screen that started it.
    int sensitiveDays =
        runtimeConfigService.intValue(SENSITIVE_RETENTION_KEY, SENSITIVE_RETENTION_FALLBACK_DAYS);
    int sensitive =
        jdbcTemplate.update(
            "DELETE FROM studio_run r WHERE r.data_class <> 'STANDARD'"
                + " AND r.finished_at IS NOT NULL"
                + " AND r.finished_at < now() - make_interval(days => GREATEST(1, LEAST(?,"
                + "   COALESCE((SELECT (i.config ->> ?)::int FROM studio_installation i"
                + "              WHERE i.id = r.installation_id), ?))))",
            sensitiveDays,
            INSTALLATION_RETENTION_OVERRIDE,
            sensitiveDays);

    int installationDays =
        runtimeConfigService.intValue(
            INSTALLATION_RETENTION_KEY, INSTALLATION_RETENTION_FALLBACK_DAYS);
    int installations =
        jdbcTemplate.update(
            "DELETE FROM studio_installation WHERE status = ?"
                + " AND installed_at < now() - make_interval(days => ?)",
            InstallationStatus.REVOKED.name(),
            installationDays);

    long dedup = messageDedup.purgeClaimedBefore(Instant.now().minus(DEDUP_RETENTION));

    if (runs > 0 || sensitive > 0 || installations > 0 || dedup > 0) {
      logger.info(
          "Retention purge removed {} runs ({} of them protected), {} revoked installations,"
              + " {} dedup rows",
          runs + sensitive,
          sensitive,
          installations,
          dedup);
    }
  }
}
