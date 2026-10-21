package com.krizaka.orazaka.studioservice.domain.exception;

import java.io.Serial;

/**
 * An erasure could not be completed, and so was not started: nothing was deleted.
 *
 * <p>The transport answers {@code 503}. A person who asked for their data to be erased is never
 * told it is gone when part of it is not - they are told to ask again, and asking again is safe.
 */
public class ErasureIncompleteException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * @param message what could not be done, never what was being erased
   * @param cause the underlying failure, may be {@code null}
   */
  public ErasureIncompleteException(String message, Throwable cause) {
    super(message, cause);
  }
}
