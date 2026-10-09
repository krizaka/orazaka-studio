package com.krizaka.orazaka.studio.domain.model;

/**
 * The shelf entry a catalogued bundle contributes: one {@code pack} row and where it is browsed.
 *
 * @param categoryKey the shelf; must exist, or be created from {@link #category()}
 * @param iconKey resolves in the design system's icon registry
 * @param status where the pack sits in its publication lifecycle
 * @param sortWeight order within its shelf, heaviest first
 * @param category the shelf to create when absent, or {@code null} to require it already exists —
 *     an install then fails loudly rather than inventing a shelf under a typo
 */
public record PackCatalogEntry(
    String categoryKey,
    String iconKey,
    PackStatus status,
    Integer sortWeight,
    PackCategorySeed category) {

  /** Compact canonical constructor enforcing the entry's invariants (ERR-106). */
  public PackCatalogEntry {
    // Schema defaults, honoured here so an omitted optional is not a 400 (ADR-048).
    status = status == null ? PackStatus.DRAFT : status;
    sortWeight = sortWeight == null ? 0 : sortWeight;
    if (categoryKey == null || categoryKey.isBlank()) {
      throw new IllegalArgumentException("categoryKey must not be blank");
    }
    if (iconKey == null || iconKey.isBlank()) {
      throw new IllegalArgumentException("iconKey must not be blank");
    }
    status = status == null ? PackStatus.DRAFT : status;
  }
}
