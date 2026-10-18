package com.krizaka.orazaka.studioservice.domain.exception;

/**
 * An {@code Idempotency-Key} that already started a run is presented with a different request.
 *
 * <p>Answering the first run would hand the caller a result for something it did not ask; starting
 * a second one would defeat the key. The only honest answer is a refusal ({@code 422}).
 */
public class IdempotencyKeyReusedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the refusal. */
  public IdempotencyKeyReusedException() {
    super("this Idempotency-Key was already used for a different request");
  }
}
