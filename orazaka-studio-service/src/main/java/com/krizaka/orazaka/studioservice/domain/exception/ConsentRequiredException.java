package com.krizaka.orazaka.studioservice.domain.exception;

import com.krizaka.orazaka.studio.domain.model.CrisisResource;
import java.io.Serial;
import java.util.List;
import java.util.Map;

/**
 * An installation of a {@code REGULATED} pack was attempted without the consent it requires, or
 * with consent to a statement that has since changed.
 *
 * <p>Distinct from a validation failure so the transport can answer with the statement the user
 * still has to agree to, rather than with "bad request" — the caller is not wrong, they have not
 * been asked yet.
 */
public class ConsentRequiredException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final String studioKey;
  private final String requiredVersion;
  private final String statement;
  private final Map<String, String> statements;
  private final List<CrisisResource> regions;

  /**
   * @param studioKey the Studio being installed
   * @param requiredVersion the consent version now in force
   * @param statement what the user is being asked to agree to, in the caller's language
   */
  public ConsentRequiredException(String studioKey, String requiredVersion, String statement) {
    this(studioKey, requiredVersion, statement, Map.of(), List.of());
  }

  /**
   * @param studioKey the Studio being installed
   * @param requiredVersion the consent version now in force
   * @param statement what the user is being asked to agree to, in the caller's language
   * @param statements the same statement in every language the pack ships, keyed by language
   * @param regions the regions the pack installs in, each with its verified crisis line - shown
   *     before the person agrees, so the line they would be given is the line they have seen
   */
  public ConsentRequiredException(
      String studioKey,
      String requiredVersion,
      String statement,
      Map<String, String> statements,
      List<CrisisResource> regions) {
    super(
        "installing "
            + studioKey
            + " requires consent to version "
            + requiredVersion
            + "; none was recorded, or it was given to an earlier statement");
    this.studioKey = studioKey;
    this.requiredVersion = requiredVersion;
    this.statement = statement;
    this.statements = statements == null ? Map.of() : Map.copyOf(statements);
    this.regions = regions == null ? List.of() : List.copyOf(regions);
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

  /**
   * @return the statement in every language the pack ships, keyed by language
   */
  public Map<String, String> statements() {
    return statements;
  }

  /**
   * @return the regions the pack installs in, each with its verified crisis line
   */
  public List<CrisisResource> regions() {
    return regions;
  }
}
