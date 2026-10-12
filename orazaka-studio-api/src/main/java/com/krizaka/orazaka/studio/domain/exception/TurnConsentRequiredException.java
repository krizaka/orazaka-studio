package com.krizaka.orazaka.studio.domain.exception;

/**
 * A conversational turn was asked under an installation whose recorded consent is not to the
 * statement its pack now requires (ADR-055 §3, ADR-076).
 *
 * <p>Carries the version and the statement so the transport answers {@code 451} with the exact text
 * to agree to — a client renders the pack's words, never wording of its own for a legal notice.
 */
public class TurnConsentRequiredException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String requiredVersion;
  private final String statement;

  /**
   * @param requiredVersion the consent version now in force
   * @param statement what the person must agree to
   */
  public TurnConsentRequiredException(String requiredVersion, String statement) {
    super(
        "this installation's consent is not to version "
            + requiredVersion
            + " of its pack's statement; the turn is not taken until it is");
    this.requiredVersion = requiredVersion;
    this.statement = statement;
  }

  /**
   * @return the version the person must consent to
   */
  public String requiredVersion() {
    return requiredVersion;
  }

  /**
   * @return the statement to show them, verbatim
   */
  public String statement() {
    return statement;
  }
}
