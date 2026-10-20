package com.krizaka.orazaka.studioservice.domain.exception;

import java.io.Serial;

/**
 * An actor already has as many runs in flight as the platform allows (the {@code
 * run.max-concurrent-per-actor} row, ADR-083).
 *
 * <p>A flow-control refusal, not a conflict: the same call succeeds once a run finishes, so the
 * answer is {@code 429} with {@code Retry-After}. It stays an {@link IllegalStateException} so code
 * that already catches the old refusal keeps working.
 */
public class TooManyConcurrentRunsException extends IllegalStateException {

  @Serial private static final long serialVersionUID = 1L;

  private final int active;
  private final int limit;
  private final int retryAfterSeconds;

  /**
   * Creates the refusal.
   *
   * @param active runs the actor has in flight
   * @param limit runs it may have
   * @param retryAfterSeconds when to try again
   */
  public TooManyConcurrentRunsException(int active, int limit, int retryAfterSeconds) {
    super("too many concurrent runs: " + active + " of " + limit + " already running");
    this.active = active;
    this.limit = limit;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  /**
   * @return runs the actor has in flight
   */
  public int active() {
    return active;
  }

  /**
   * @return runs it may have
   */
  public int limit() {
    return limit;
  }

  /**
   * @return seconds to wait before trying again
   */
  public int retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
