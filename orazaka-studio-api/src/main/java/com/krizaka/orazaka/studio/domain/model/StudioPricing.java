package com.krizaka.orazaka.studio.domain.model;

/**
 * How an actor obtains the right to install a {@link Studio}.
 *
 * <p>Orthogonal to credits: pricing gates <b>access</b>, credits gate <b>volume</b> (ADR-033).
 * Every mode still consumes credits per run, so a {@link #FREE} Studio is free to install, never
 * free to execute.
 *
 * <p>The constant names are the vocabulary of {@code studio.pricing} and of its {@code
 * studio_pricing_needs_package} CHECK — renaming one without the other silently breaks the seed.
 */
public enum StudioPricing {

  /** Anyone may install it. Runs are still charged against the wallet. */
  FREE,

  /** Granted by the actor's plan entitlements — no separate purchase. */
  INCLUDED,

  /**
   * Requires buying the linked billing package. A {@code PAID} Studio without a pack key is
   * unsellable and unreachable, which is why {@link Studio} rejects that combination.
   */
  PAID
}
