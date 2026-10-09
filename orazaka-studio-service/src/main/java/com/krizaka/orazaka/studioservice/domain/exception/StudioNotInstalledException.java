package com.krizaka.orazaka.studioservice.domain.exception;

/**
 * Thrown when an actor asks to run a VERTICAL Studio they are entitled to but have not installed.
 *
 * <p>Refused in the <b>same shape</b> as an entitlement refusal — {@code 409}, the same body keys,
 * the pack to open — and deliberately so (ADR-061). From the caller's side "you have not installed
 * this VERTICAL" and "you are not entitled to this TOOLKIT" are one situation, the Studio is not
 * available to you yet, and one client branch should handle both by opening the pack. Two refusal
 * shapes is how a UI ends up with two code paths and one of them wrong. Only the {@code status} and
 * {@code remedies} tokens differ, and they say which action that pack page will offer.
 */
public class StudioNotInstalledException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String studioKey;
  private final String entitlementKey;
  private final String packKey;

  /**
   * @param studioKey the Studio that was asked for
   * @param entitlementKey the key the actor does hold for it
   * @param packKey the pack it is sold under, to open; {@code null} when it belongs to none
   */
  public StudioNotInstalledException(String studioKey, String entitlementKey, String packKey) {
    super("Studio '" + studioKey + "' is not installed for this actor");
    this.studioKey = studioKey;
    this.entitlementKey = entitlementKey;
    this.packKey = packKey;
  }

  /**
   * @return the Studio that was asked for
   */
  public String studioKey() {
    return studioKey;
  }

  /**
   * @return the entitlement key the actor holds
   */
  public String entitlementKey() {
    return entitlementKey;
  }

  /**
   * @return the pack to open, or {@code null}
   */
  public String packKey() {
    return packKey;
  }
}
