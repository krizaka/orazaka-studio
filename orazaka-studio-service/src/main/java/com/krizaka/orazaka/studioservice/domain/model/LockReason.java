package com.krizaka.orazaka.studioservice.domain.model;

/**
 * Why a Studio is not installable by this actor — the copy the upsell renders.
 *
 * <p>The distinctions are the product, not bookkeeping: "buy this package" and "upgrade your plan"
 * lead to different screens, and "billing is unreachable" must never be shown as either, because
 * telling a paying customer they are not entitled during an outage is the one failure that costs
 * trust rather than a click.
 */
public enum LockReason {

  /** Installable. */
  NONE,

  /** A paid Studio the actor has not bought — the catalogue shows the pack and a buy button. */
  REQUIRES_PURCHASE,

  /** Included with a higher plan the actor is not on — the catalogue shows an upgrade link. */
  REQUIRES_PLAN,

  /**
   * Billing could not be reached, so entitlement is unknown. Rendered as a transient error, never
   * as a refusal: an unresolved snapshot says nothing about the actor (ADR-033).
   */
  UNKNOWN
}
