package com.krizaka.orazaka.studioservice.domain.exception;

import java.util.UUID;

/**
 * Thrown when a run id names nothing <b>this actor owns</b>.
 *
 * <p>Indistinguishable from "does not exist", like its installation counterpart: a run carries the
 * actor's own material, so confirming another tenant's run exists is a disclosure in itself.
 */
public class RunNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final UUID runId;

  /**
   * @param runId the id that resolved to nothing for this actor
   */
  public RunNotFoundException(UUID runId) {
    super("No such run: " + runId);
    this.runId = runId;
  }

  /**
   * @return the id that resolved to nothing for this actor
   */
  public UUID runId() {
    return runId;
  }
}
