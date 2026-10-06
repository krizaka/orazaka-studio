package com.orazaka.studio.domain.exception;

/**
 * Thrown when an actor asks to install or run a Studio their entitlements do not grant.
 *
 * <p>Carries {@link #packKey()} because the refusal is the top of the funnel, not a dead end: the
 * REST layer answers {@code 409} with the pack to buy so the UI can open checkout directly. Locked
 * Studios are shown greyed with the upsell rather than hidden, and hiding them is what destroys the
 * funnel (ADR-034 §4).
 *
 * <p>Distinct from an insufficient-credits refusal on purpose: entitlement gates <b>access</b>,
 * credits gate <b>volume</b> (ADR-033), and the UI must be able to say which of the two happened.
 */
public class StudioNotEntitledException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String studioKey;
  private final String entitlementKey;
  private final String packKey;

  /**
   * @param studioKey the Studio that was refused
   * @param entitlementKey the key the actor's snapshot did not grant
   * @param packKey the pack that would grant it, or {@code null} when no purchase unlocks it
   */
  public StudioNotEntitledException(String studioKey, String entitlementKey, String packKey) {
    super("Actor is not entitled to studio '" + studioKey + "' (requires " + entitlementKey + ")");
    this.studioKey = studioKey;
    this.entitlementKey = entitlementKey;
    this.packKey = packKey;
  }

  /**
   * @return the Studio that was refused
   */
  public String studioKey() {
    return studioKey;
  }

  /**
   * @return the entitlement key the actor's snapshot did not grant
   */
  public String entitlementKey() {
    return entitlementKey;
  }

  /**
   * @return the pack that would unlock it, or {@code null} when the refusal is not purchasable away
   *     — an {@code INCLUDED} Studio on a plan that does not carry it
   */
  public String packKey() {
    return packKey;
  }
}
