package com.orazaka.studio.domain.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StudioTest {

  private static final Instant UPDATED = Instant.parse("2026-08-05T10:00:00Z");

  private static Studio studio(StudioPricing pricing, String packKey) {
    return new Studio(
        "realestate-reels",
        "Reels Immobilier",
        "Cinq photos deviennent un Reel.",
        "real-estate",
        "studio",
        null,
        pricing,
        packKey,
        PackKind.VERTICAL,
        "studio.realestate-reels",
        StudioStatus.PUBLISHED,
        "orazaka",
        "1.0.0",
        Set.of("fr", "en"),
        UPDATED);
  }

  @Test
  void accepts_aFullyPopulatedPaidStudio() {
    Studio studio = studio(StudioPricing.PAID, "realestate-studio");

    assertEquals("realestate-reels", studio.studioKey());
    assertEquals("studio.realestate-reels", studio.entitlementKey());
  }

  @Test
  void rejects_aPaidStudioWithNoPackage_mirroringTheSqlCheck() {
    // Unsellable AND unreachable: the install path would have nothing to open a checkout for.
    assertThrows(IllegalArgumentException.class, () -> studio(StudioPricing.PAID, null));
    assertThrows(IllegalArgumentException.class, () -> studio(StudioPricing.PAID, "  "));
  }

  @Test
  void accepts_aFreeStudioWithNoPackage() {
    assertDoesNotThrow(() -> studio(StudioPricing.FREE, null));
    assertDoesNotThrow(() -> studio(StudioPricing.INCLUDED, null));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Realestate-Reels", "realestate_reels", "realestate reels", "-reels", ""})
  void rejects_aKeyThatIsNotAUsableUrlSegmentOrEntitlementSuffix(String studioKey) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Studio(
                studioKey,
                "Reels",
                null,
                "real-estate",
                "studio",
                null,
                StudioPricing.FREE,
                null,
                PackKind.VERTICAL,
                "studio.reels",
                StudioStatus.DRAFT,
                "orazaka",
                null,
                Set.of(),
                UPDATED));
  }

  @Test
  void rejects_aBlankLabelProfessionIconOrEntitlementKey() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Studio(
                "reels",
                " ",
                null,
                "trades",
                "studio",
                null,
                StudioPricing.FREE,
                null,
                PackKind.VERTICAL,
                "studio.reels",
                StudioStatus.DRAFT,
                "orazaka",
                null,
                Set.of(),
                UPDATED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Studio(
                "reels",
                "Reels",
                null,
                "",
                "studio",
                null,
                StudioPricing.FREE,
                null,
                PackKind.VERTICAL,
                "studio.reels",
                StudioStatus.DRAFT,
                "orazaka",
                null,
                Set.of(),
                UPDATED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Studio(
                "reels",
                "Reels",
                null,
                "trades",
                " ",
                null,
                StudioPricing.FREE,
                null,
                PackKind.VERTICAL,
                "studio.reels",
                StudioStatus.DRAFT,
                "orazaka",
                null,
                Set.of(),
                UPDATED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Studio(
                "reels",
                "Reels",
                null,
                "trades",
                "studio",
                null,
                StudioPricing.FREE,
                null,
                PackKind.VERTICAL,
                "",
                StudioStatus.DRAFT,
                "orazaka",
                null,
                Set.of(),
                UPDATED));
  }

  @Test
  void rejects_nullPricingStatusOrTimestamp() {
    assertThrows(
        NullPointerException.class,
        () ->
            new Studio(
                "reels",
                "Reels",
                null,
                "trades",
                "studio",
                null,
                null,
                null,
                PackKind.VERTICAL,
                "studio.reels",
                StudioStatus.DRAFT,
                "orazaka",
                null,
                Set.of(),
                UPDATED));
    assertThrows(
        NullPointerException.class,
        () ->
            new Studio(
                "reels",
                "Reels",
                null,
                "trades",
                "studio",
                null,
                StudioPricing.FREE,
                null,
                PackKind.VERTICAL,
                "studio.reels",
                null,
                "orazaka",
                null,
                Set.of(),
                UPDATED));
    assertThrows(
        NullPointerException.class,
        () ->
            new Studio(
                "reels",
                "Reels",
                null,
                "trades",
                "studio",
                null,
                StudioPricing.FREE,
                null,
                PackKind.VERTICAL,
                "studio.reels",
                StudioStatus.DRAFT,
                "orazaka",
                null,
                Set.of(),
                null));
  }

  @Test
  void copiesTheLocales_andTreatsNullAsNone() {
    Set<String> source = new HashSet<>(Set.of("fr"));
    Studio studio =
        new Studio(
            "reels",
            "Reels",
            null,
            "trades",
            "studio",
            null,
            StudioPricing.FREE,
            null,
            PackKind.VERTICAL,
            "studio.reels",
            StudioStatus.DRAFT,
            "orazaka",
            null,
            source,
            UPDATED);

    source.add("en");

    assertEquals(Set.of("fr"), studio.locales());
    assertTrue(
        new Studio(
                "reels",
                "Reels",
                null,
                "trades",
                "studio",
                null,
                StudioPricing.FREE,
                null,
                PackKind.VERTICAL,
                "studio.reels",
                StudioStatus.DRAFT,
                "orazaka",
                null,
                null,
                UPDATED)
            .locales()
            .isEmpty());
  }
}
