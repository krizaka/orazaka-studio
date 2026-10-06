package com.orazaka.studioservice.domain.exception;

/**
 * Thrown when a Studio key names nothing in the catalogue.
 *
 * <p>Distinct from a Studio that exists but is locked: that one is a {@code 409} carrying the
 * package to buy, and conflating the two would tell a prospective buyer the product does not exist.
 */
public class StudioNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String studioKey;

  /**
   * @param studioKey the key that resolved to nothing
   */
  public StudioNotFoundException(String studioKey) {
    super("No such studio: " + studioKey);
    this.studioKey = studioKey;
  }

  /**
   * @return the key that resolved to nothing
   */
  public String studioKey() {
    return studioKey;
  }
}
