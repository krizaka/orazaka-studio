package com.orazaka.studio.domain.model;

import java.util.regex.Pattern;

/**
 * A marketplace shelf: the top level a buyer browses by.
 *
 * <p>A <b>row</b>, not an enum, and that reversal is the point of ADR-036. The category lived in
 * billing as a {@code CHECK (category IN ('BUSINESS','LIFESTYLE'))}, defended there because "a
 * shelf nobody can name in the front-end is a shelf nobody browses". The reasoning was right and it
 * is exactly what a CHECK cannot deliver: naming the shelf needs a French label, an icon and a sort
 * order. A closed vocabulary was never the requirement — an unnameable one was the defect.
 *
 * <p>Closed in spirit all the same: categories stay admin-only and ~5 rows ever, and a governance
 * test asserts that every {@code pack.category_key} resolves to one.
 *
 * @param categoryKey stable kebab-case identity, e.g. {@code business}; it is a URL query value and
 *     a grouping key, so it carries no case and no spaces
 * @param label the localised shelf name — "Business", "Life Style"
 * @param description one localised line under the shelf heading, {@code null} when the locale
 *     carries none
 * @param iconKey resolves in the design system's centralised icon registry
 * @param sortWeight shelf order on the marketplace page, heaviest first
 * @param active whether the shelf is offered at all
 */
public record PackCategory(
    String categoryKey,
    String label,
    String description,
    String iconKey,
    int sortWeight,
    boolean active) {

  /** Same grammar as every other catalogue key on this platform. */
  private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9-]{0,29}$");

  /** Compact canonical constructor enforcing the contract's invariants (ERR-106). */
  public PackCategory {
    if (categoryKey == null || !KEY.matcher(categoryKey).matches()) {
      throw new IllegalArgumentException(
          "categoryKey must match " + KEY.pattern() + ", was: " + categoryKey);
    }
    // A shelf with no label renders as a raw key, which is the failure this type exists to make
    // impossible — the whole reason the category stopped being a CHECK.
    if (label == null || label.isBlank()) {
      throw new IllegalArgumentException("label must not be blank");
    }
    if (iconKey == null || iconKey.isBlank()) {
      throw new IllegalArgumentException("iconKey must not be blank");
    }
  }
}
