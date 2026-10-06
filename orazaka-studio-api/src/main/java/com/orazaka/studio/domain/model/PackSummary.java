package com.orazaka.studio.domain.model;

import java.util.Objects;

/**
 * One marketplace card: a Pack, the shelf it sits on, and what it costs.
 *
 * <p>The price is {@code null} rather than zero when billing could not be reached. The distinction
 * is load-bearing — zero means free, absent means unknown, and a marketplace that renders "Gratuit"
 * because the billing service is restarting has invented a price it will be held to. The UI renders
 * an absent price as "—" (ADR-036 §4.4).
 *
 * <p>The category travels with the card instead of being re-resolved per row: browsing is one query
 * and one join, not one lookup per Pack.
 *
 * @param pack the catalogue row, already localised
 * @param category the shelf it is browsed under; its key must match the Pack's
 * @param priceCents the list price, or {@code null} when billing is unreachable
 * @param includedCredits credits bundled with the purchase, or {@code null} when billing is
 *     unreachable
 */
public record PackSummary(
    Pack pack, PackCategory category, Integer priceCents, Long includedCredits) {

  /** Compact canonical constructor enforcing the contract's invariants (ERR-106). */
  public PackSummary {
    Objects.requireNonNull(pack, "pack must not be null");
    Objects.requireNonNull(category, "category must not be null");
    // A card that shows one shelf's heading over another shelf's product is the kind of defect
    // that survives review because each half is individually correct.
    if (!category.categoryKey().equals(pack.categoryKey())) {
      throw new IllegalArgumentException(
          "category " + category.categoryKey() + " does not match pack " + pack.categoryKey());
    }
    if (priceCents != null && priceCents < 0) {
      throw new IllegalArgumentException("priceCents must be >= 0, was: " + priceCents);
    }
    if (includedCredits != null && includedCredits < 0) {
      throw new IllegalArgumentException("includedCredits must be >= 0, was: " + includedCredits);
    }
  }

  /**
   * Whether billing answered for this Pack.
   *
   * @return {@code true} when a price is known — the card may render it; {@code false} when the
   *     catalogue degraded and the card must render "—"
   */
  public boolean priced() {
    return priceCents != null;
  }
}
