package com.krizaka.orazaka.studio.domain.model;

/**
 * A shelf a bundle is willing to create if it is the first pack to land on it.
 *
 * <p>Creation is <b>additive only</b>: an existing category keeps its icon, weight and
 * translations. A bundle may open a shelf; it may not restyle one that other packs are already
 * browsed under.
 *
 * @param iconKey the shelf's icon, used only when the category is created
 * @param sortWeight the shelf's order, used only when the category is created
 */
public record PackCategorySeed(String iconKey, Integer sortWeight) {

  /** Compact canonical constructor enforcing the seed's invariants (ERR-106). */
  public PackCategorySeed {
    // Schema default, honoured here so an omitted optional is not a 400 (ADR-048).
    sortWeight = sortWeight == null ? 0 : sortWeight;
    if (iconKey == null || iconKey.isBlank()) {
      throw new IllegalArgumentException("category iconKey must not be blank");
    }
  }
}
