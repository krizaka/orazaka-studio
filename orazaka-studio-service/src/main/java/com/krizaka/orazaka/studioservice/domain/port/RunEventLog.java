package com.krizaka.orazaka.studioservice.domain.port;

import java.util.OptionalLong;
import java.util.UUID;

/**
 * The numbering of a run's changes (orazaka#241, ADR-083 slice 10).
 *
 * <p>The database numbers them — one by one, per run, whichever instance writes — so every instance
 * and every reconnecting client agrees on the numbers; nothing is kept in memory.
 */
public interface RunEventLog {

  /**
   * The number of the run's latest change.
   *
   * @param runId the run
   * @param actorId the opaque billable subject
   * @return the number (0 for a run that predates the numbering), or empty when the actor owns no
   *     such run
   */
  OptionalLong currentSeq(UUID runId, String actorId);
}
