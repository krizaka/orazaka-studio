package com.orazaka.studioservice.domain.exception;

/**
 * Thrown when an actor asks to install a TOOLKIT Studio: there is nothing to install.
 *
 * <p><b>An error, not a no-op, and the choice is argued in ADR-061.</b> An install's contract is an
 * addressable installation — an id, a pinned version, a configuration — that the configure,
 * upgrade, uninstall and run endpoints then consume. A TOOLKIT's installation is derived from
 * entitlement and has none of the three. A successful no-op would have to invent that address,
 * which fails later, on the next call, far from the assumption that caused it; writing a row to
 * make the address real would delete the one property a TOOLKIT exists to have. This refusal states
 * the truth at the call that made the wrong assumption.
 *
 * <p>Raised only for an actor who <i>is</i> entitled — an unentitled one gets the entitlement
 * refusal first, as for any Studio — so its remedy is always to run the Studio directly.
 */
public class StudioIncludedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String studioKey;
  private final String entitlementKey;
  private final String packKey;

  /**
   * @param studioKey the TOOLKIT Studio that was asked to be installed
   * @param entitlementKey the key that already grants it
   * @param packKey the TOOLKIT pack it belongs to
   */
  public StudioIncludedException(String studioKey, String entitlementKey, String packKey) {
    super(
        "Studio '"
            + studioKey
            + "' is included in pack '"
            + packKey
            + "': it has no installation to create, run it directly");
    this.studioKey = studioKey;
    this.entitlementKey = entitlementKey;
    this.packKey = packKey;
  }

  /**
   * @return the TOOLKIT Studio
   */
  public String studioKey() {
    return studioKey;
  }

  /**
   * @return the key that grants it
   */
  public String entitlementKey() {
    return entitlementKey;
  }

  /**
   * @return the TOOLKIT pack it belongs to
   */
  public String packKey() {
    return packKey;
  }
}
