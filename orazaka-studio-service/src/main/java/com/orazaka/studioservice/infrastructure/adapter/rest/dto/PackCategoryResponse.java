package com.orazaka.studioservice.infrastructure.adapter.rest.dto;

import com.orazaka.studio.domain.model.PackCategory;

/**
 * One marketplace shelf heading.
 *
 * <p>Fetched rather than hardcoded in the client, which is the whole point of ADR-036: the shelves
 * used to be a closed TypeScript enum mirroring a SQL {@code CHECK}, so adding one meant editing
 * three files in two languages and inventing a translation nobody had written. A shelf is now a
 * row, and its label arrives already localised.
 *
 * @param categoryKey stable key — the grouping key the cards carry
 * @param label the localised shelf name
 * @param description one localised line under the heading, may be {@code null}
 * @param iconKey resolves in the design system's icon registry
 * @param sortWeight shelf order, heaviest first
 */
public record PackCategoryResponse(
    String categoryKey, String label, String description, String iconKey, int sortWeight) {

  /**
   * Projects a shelf onto the wire.
   *
   * @param category the catalogue row
   * @return the heading
   */
  public static PackCategoryResponse from(PackCategory category) {
    return new PackCategoryResponse(
        category.categoryKey(),
        category.label(),
        category.description(),
        category.iconKey(),
        category.sortWeight());
  }
}
