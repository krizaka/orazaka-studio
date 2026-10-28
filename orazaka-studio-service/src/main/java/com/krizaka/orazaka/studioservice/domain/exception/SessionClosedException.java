package com.krizaka.orazaka.studioservice.domain.exception;

import java.io.Serial;
import java.util.UUID;

/**
 * A turn was sent to a session that is over: finished by the client, cancelled, expired after its
 * idle TTL, or failed. A conflict (409), and the reason is said, so the client does not guess.
 */
public class SessionClosedException extends IllegalStateException {

  @Serial private static final long serialVersionUID = 1L;

  /** Why a session takes no more turns. */
  public enum Reason {
    /** The client said « finish ». */
    FINISHED,
    /** Cancelled by the actor. */
    CANCELLED,
    /** No turn for longer than the idle TTL. */
    EXPIRED,
    /** The run failed. */
    FAILED
  }

  private final UUID runId;
  private final Reason reason;

  /**
   * @param runId the session's run
   * @param reason why it is over
   */
  public SessionClosedException(UUID runId, Reason reason) {
    super(
        "The session is "
            + reason.name().toLowerCase(java.util.Locale.ROOT)
            + ": it takes no more turns");
    this.runId = runId;
    this.reason = reason;
  }

  /**
   * @return the session's run
   */
  public UUID runId() {
    return runId;
  }

  /**
   * @return why the session is over
   */
  public Reason reason() {
    return reason;
  }
}
