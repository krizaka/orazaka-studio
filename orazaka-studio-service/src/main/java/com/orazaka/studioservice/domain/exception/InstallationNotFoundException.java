package com.orazaka.studioservice.domain.exception;

import java.util.UUID;

/**
 * Thrown when an installation id names nothing <b>this actor owns</b>.
 *
 * <p>Deliberately indistinguishable from "does not exist": every lookup is scoped by actor, and an
 * id belonging to somebody else must answer the same way as an id belonging to nobody. Any other
 * answer confirms the existence of another tenant's data, which is the classic IDOR and the one bug
 * in this design that would be unrecoverable reputationally (ADR-034 §18).
 */
public class InstallationNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final UUID installationId;

  /**
   * @param installationId the id that resolved to nothing for this actor
   */
  public InstallationNotFoundException(UUID installationId) {
    super("No such installation: " + installationId);
    this.installationId = installationId;
  }

  /**
   * @return the id that resolved to nothing for this actor
   */
  public UUID installationId() {
    return installationId;
  }
}
