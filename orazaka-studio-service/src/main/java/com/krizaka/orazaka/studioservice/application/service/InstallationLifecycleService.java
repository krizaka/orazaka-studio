package com.krizaka.orazaka.studioservice.application.service;

import com.krizaka.orazaka.studio.domain.model.InstallationStatus;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reacts to an actor's commercial standing changing (design §14, dunning &amp; expiry).
 *
 * <p>A lapsed subscription <b>pauses</b> installations and never deletes them. That distinction is
 * the product: a plumber whose card expires on holiday must find their Studios — and the brand kit
 * they spent an afternoon configuring — exactly as they left them when they pay again. Deleting
 * would make a billing hiccup indistinguishable from a decision to leave.
 *
 * <p>Resuming is symmetrical and just as important: an actor who pays again must not have to
 * reinstall anything to discover their workspace came back.
 */
@Service
public class InstallationLifecycleService {

  private static final Logger logger = LoggerFactory.getLogger(InstallationLifecycleService.class);

  private final JdbcTemplate jdbcTemplate;

  public InstallationLifecycleService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
  }

  /**
   * Pauses every active installation of an actor whose subscription lapsed.
   *
   * <p>Revoked installations are left alone: they were uninstalled by choice, and reviving one
   * because a subscription lapsed would resurrect something the actor deliberately removed.
   *
   * @param actorId the opaque billable subject
   * @return how many installations were paused
   */
  @Transactional
  public int pauseFor(String actorId) {
    int paused =
        jdbcTemplate.update(
            "UPDATE studio_installation SET status = ? WHERE actor_id = ? AND status = ?",
            InstallationStatus.PAUSED.name(),
            actorId,
            InstallationStatus.ACTIVE.name());
    if (paused > 0) {
      logger.info("Paused {} installation(s) for actor whose subscription lapsed", paused);
    }
    return paused;
  }

  /**
   * Reactivates the installations a lapse paused.
   *
   * @param actorId the opaque billable subject
   * @return how many installations were revived
   */
  @Transactional
  public int resumeFor(String actorId) {
    int resumed =
        jdbcTemplate.update(
            "UPDATE studio_installation SET status = ? WHERE actor_id = ? AND status = ?",
            InstallationStatus.ACTIVE.name(),
            actorId,
            InstallationStatus.PAUSED.name());
    if (resumed > 0) {
      logger.info("Resumed {} installation(s) after the subscription recovered", resumed);
    }
    return resumed;
  }
}
