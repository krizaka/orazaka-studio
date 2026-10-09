package com.krizaka.orazaka.studio.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PackCategoryTest {

  private static PackCategory category(String key, String label, String iconKey) {
    return new PackCategory(key, label, "Les packs qui font le travail.", iconKey, 100, true);
  }

  @Test
  void accepts_aNameableShelf() {
    PackCategory business = category("business", "Business", "briefcase");

    assertEquals("business", business.categoryKey());
    assertEquals("Business", business.label());
    assertEquals(100, business.sortWeight());
  }

  @Test
  void rejects_aShelfWithNoLabel_whichIsTheDefectTheCheckConstraintCouldNotPrevent() {
    // The whole reason the category stopped being CHECK (category IN (…)): an unlabelled
    // shelf renders as a raw key, and a shelf nobody can name is a shelf nobody browses.
    assertThrows(IllegalArgumentException.class, () -> category("business", null, "briefcase"));
    assertThrows(IllegalArgumentException.class, () -> category("business", "  ", "briefcase"));
  }

  @Test
  void rejects_aShelfWithNoIcon_becauseTheMarketplaceRendersOneWhetherOrNotItWasChosen() {
    assertThrows(IllegalArgumentException.class, () -> category("business", "Business", null));
    assertThrows(IllegalArgumentException.class, () -> category("business", "Business", " "));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Business", "life style", "1business", "-business", ""})
  void rejects_aKeyThatCannotBeAUrlValue(String key) {
    assertThrows(IllegalArgumentException.class, () -> category(key, "Business", "briefcase"));
  }

  @Test
  void rejects_aNullKey() {
    assertThrows(IllegalArgumentException.class, () -> category(null, "Business", "briefcase"));
  }

  @Test
  void allowsANullDescription_becauseALocaleMayCarryTheLabelAndNotTheCopy() {
    assertEquals(
        null, new PackCategory("lifestyle", "Life Style", null, "heart", 90, true).description());
  }
}
