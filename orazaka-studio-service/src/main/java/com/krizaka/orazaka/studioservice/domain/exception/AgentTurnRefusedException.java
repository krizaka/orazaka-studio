package com.krizaka.orazaka.studioservice.domain.exception;

import java.io.Serial;

/**
 * Thrown when the engine refuses a turn. The code and the HTTP status are the engine's own words
 * ({@code studio_not_entitled}, {@code consent_required}, {@code insufficient_credits}, or {@code
 * engine_unavailable} when it could not be reached), passed on so the client ends the run with the
 * engine's reason, never one invented here.
 */
public class AgentTurnRefusedException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final String code;
  private final int httpStatus;

  /**
   * @param code the engine's status code
   * @param httpStatus the HTTP status to answer with
   */
  public AgentTurnRefusedException(String code, int httpStatus) {
    super("The engine refused the turn: " + code);
    this.code = code;
    this.httpStatus = httpStatus;
  }

  /**
   * @return the engine's status code
   */
  public String code() {
    return code;
  }

  /**
   * @return the HTTP status to answer with
   */
  public int httpStatus() {
    return httpStatus;
  }
}
