package com.orazaka.studioservice.domain.exception;

/**
 * A pack bundle could not be installed.
 *
 * <p>Carries the reason as its message because the caller is an operator running {@code orazaka
 * pack install}, and the only useful thing to hand them is which capability did not resolve or
 * which slice refused the write. A stack trace on a terminal is not an error message.
 */
public class PackInstallException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what was wrong with the bundle, or which slice failed
   */
  public PackInstallException(String message) {
    super(message);
  }

  /**
   * Creates the exception.
   *
   * @param message which bundle failed
   * @param cause the slice failure underneath
   */
  public PackInstallException(String message, Throwable cause) {
    super(message, cause);
  }
}
