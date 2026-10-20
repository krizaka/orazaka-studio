package com.krizaka.orazaka.studioservice.domain.exception;

import java.io.Serial;

/**
 * An actor holds as many webhook endpoints as allowed (the {@code webhook.max-endpoints-per-actor}
 * row).
 */
public class TooManyWebhookEndpointsException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final int limit;

  /**
   * @param limit how many endpoints one actor may hold
   */
  public TooManyWebhookEndpointsException(int limit) {
    super("too many webhook endpoints: at most " + limit);
    this.limit = limit;
  }

  /**
   * @return how many endpoints one actor may hold
   */
  public int limit() {
    return limit;
  }
}
