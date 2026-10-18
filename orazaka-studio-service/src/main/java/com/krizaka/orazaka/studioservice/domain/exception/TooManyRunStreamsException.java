package com.krizaka.orazaka.studioservice.domain.exception;

/**
 * An actor already watches as many runs as the platform allows (the {@code
 * run.max-streams-per-actor} row): every open stream holds a connection and a thread, so the number
 * is bounded.
 */
public class TooManyRunStreamsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int limit;

  /**
   * Creates the refusal.
   *
   * @param limit how many streams one actor may hold open
   */
  public TooManyRunStreamsException(int limit) {
    super("too many open run streams: at most " + limit + " at once");
    this.limit = limit;
  }

  /**
   * How many streams one actor may hold open.
   *
   * @return the limit
   */
  public int limit() {
    return limit;
  }
}
