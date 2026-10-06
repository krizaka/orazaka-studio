package com.orazaka.studioservice.domain.exception;

/**
 * An installation of a {@code REGULATED} pack was attempted without the consent it requires, or
 * with consent to a statement that has since changed.
 *
 * <p>Distinct from a validation failure so the transport can answer with the statement the user
 * still has to agree to, rather than with "bad request" — the caller is not wrong, they have not
 * been asked yet.
 */
public class ConsentRequiredException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String studioKey;
  private final String requiredVersion;
  private final String statement;

  /**
   * @param studioKey the Studio being installed
   * @param requiredVersion the consent version now in force
   * @param statement what the user is being asked to agree to
   */
  public ConsentRequiredException(String studioKey, String requiredVersion, String statement) {
    super(
        "installing "
            + studioKey
            + " requires consent to version "
            + requiredVersion
            + "; none was recorded, or it was given to an earlier statement");
    this.studioKey = studioKey;
    this.requiredVersion = requiredVersion;
    this.statement = statement;
  }

  /**
   * @return the Studio
   */
  public String studioKey() {
    return studioKey;
  }

  /**
   * @return the version the caller must consent to
   */
  public String requiredVersion() {
    return requiredVersion;
  }

  /**
   * @return the statement to show them
   */
  public String statement() {
    return statement;
  }
}
