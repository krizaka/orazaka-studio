package com.krizaka.orazaka.studio.domain.model;

/**
 * The three strings every localisable row in this context carries.
 *
 * <p>One shape for packs, categories and Studios, because {@code pack_i18n}, {@code
 * pack_category_i18n} and {@code studio_i18n} are the same three columns — and the reason the
 * catalogue moved into this context at all was to keep exactly one i18n mechanism (ADR-036).
 *
 * @param label the product name — the outcome, never the mechanism
 * @param tagline one selling line for the card, {@code null} when none
 * @param description the long copy for the detail screen, {@code null} when none
 */
public record LocalisedText(String label, String tagline, String description) {

  /** Compact canonical constructor enforcing the text's invariants (ERR-106). */
  public LocalisedText {
    if (label == null || label.isBlank()) {
      throw new IllegalArgumentException("a localised entry must carry a label");
    }
  }
}
