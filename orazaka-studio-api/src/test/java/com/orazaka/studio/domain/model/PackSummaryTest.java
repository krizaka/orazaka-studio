package com.orazaka.studio.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PackSummaryTest {

  private static final PackCategory BUSINESS =
      new PackCategory("business", "Business", null, "briefcase", 100, true);

  private static final Pack PACK =
      new Pack(
          "realestate-studio",
          "business",
          "Studio Immobilier",
          "Vos biens deviennent des Reels.",
          null,
          "studio",
          null,
          RegulatoryClass.STANDARD,
          PackKind.VERTICAL,
          PackStatus.PUBLISHED,
          100,
          List.of("realestate-reels"),
          Set.of("fr", "en"),
          Instant.parse("2026-08-27T10:00:00Z"));

  @Test
  void carriesThePrice_whenBillingAnswered() {
    PackSummary summary = new PackSummary(PACK, BUSINESS, 4900, 5000L);

    assertTrue(summary.priced());
    assertEquals(4900, summary.priceCents());
    assertEquals(5000L, summary.includedCredits());
  }

  @Test
  void reportsAnAbsentPriceAsUnknown_neverAsFree() {
    // Zero means free, absent means billing is unreachable. Collapsing the two would have the
    // marketplace advertise a price it never agreed to while the billing service restarts.
    PackSummary degraded = new PackSummary(PACK, BUSINESS, null, null);

    assertFalse(degraded.priced());
    assertNull(degraded.priceCents());

    assertTrue(new PackSummary(PACK, BUSINESS, 0, 0L).priced());
  }

  @Test
  void rejects_aCardWhoseShelfIsNotThePacksShelf() {
    PackCategory lifestyle = new PackCategory("lifestyle", "Life Style", null, "heart", 90, true);

    assertThrows(IllegalArgumentException.class, () -> new PackSummary(PACK, lifestyle, 4900, 0L));
  }

  @Test
  void rejects_aNegativePriceOrCreditGrant() {
    assertThrows(IllegalArgumentException.class, () -> new PackSummary(PACK, BUSINESS, -1, 0L));
    assertThrows(IllegalArgumentException.class, () -> new PackSummary(PACK, BUSINESS, 0, -1L));
  }

  @Test
  void rejects_aCardWithNoPackOrNoShelf() {
    assertThrows(NullPointerException.class, () -> new PackSummary(null, BUSINESS, 0, 0L));
    assertThrows(NullPointerException.class, () -> new PackSummary(PACK, null, 0, 0L));
  }
}
