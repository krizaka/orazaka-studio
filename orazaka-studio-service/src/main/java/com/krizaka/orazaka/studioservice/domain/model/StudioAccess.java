package com.krizaka.orazaka.studioservice.domain.model;

import com.krizaka.orazaka.studio.domain.model.Studio;
import com.krizaka.orazaka.studio.domain.model.StudioPricing;
import java.util.Objects;

/**
 * Whether one actor may install one Studio, and what to show them if not.
 *
 * <p>The decision is made once and read twice: cosmetically by the catalogue, authoritatively by
 * the install and run paths. Both call {@link #of} rather than re-deriving the rule, because a
 * catalogue that greys a Studio the install path would accept — or vice versa — is worse than
 * either behaviour alone.
 *
 * @param locked whether the actor is blocked from installing
 * @param reason why, {@link LockReason#NONE} when they are not
 * @param packKey the pack that would unlock it, {@code null} when no purchase applies
 */
public record StudioAccess(boolean locked, LockReason reason, String packKey) {

  private static final StudioAccess OPEN = new StudioAccess(false, LockReason.NONE, null);

  /** Compact canonical constructor keeping the pair consistent (ERR-106). */
  public StudioAccess {
    Objects.requireNonNull(reason, "reason must not be null");
    if (locked == (reason == LockReason.NONE)) {
      throw new IllegalArgumentException("locked and reason disagree: " + locked + " / " + reason);
    }
  }

  /**
   * Decides access from the Studio's pricing and what the actor's entitlements grant.
   *
   * <p>{@code resolved} is honoured deliberately: when billing is unreachable the snapshot says
   * nothing about the actor, so {@code FREE} and {@code INCLUDED} pass through — refusing them
   * would lock every user out of Studios they already have during a billing outage — while {@code
   * PAID} is held back as {@link LockReason#UNKNOWN}, never as a denial.
   *
   * @param studio the Studio being evaluated
   * @param granted whether the actor's snapshot grants the Studio's entitlement key
   * @param resolved whether the snapshot reflects a real plan at all
   * @return the decision, with the pack to buy when one applies
   */
  public static StudioAccess of(Studio studio, boolean granted, boolean resolved) {
    Objects.requireNonNull(studio, "studio must not be null");
    if (granted) {
      return OPEN;
    }
    if (!resolved) {
      return studio.pricing() == StudioPricing.PAID
          ? new StudioAccess(true, LockReason.UNKNOWN, studio.packKey())
          : OPEN;
    }
    return studio.pricing() == StudioPricing.PAID
        ? new StudioAccess(true, LockReason.REQUIRES_PURCHASE, studio.packKey())
        : new StudioAccess(true, LockReason.REQUIRES_PLAN, null);
  }

  /**
   * @return the decision for a Studio nothing blocks
   */
  public static StudioAccess open() {
    return OPEN;
  }
}
