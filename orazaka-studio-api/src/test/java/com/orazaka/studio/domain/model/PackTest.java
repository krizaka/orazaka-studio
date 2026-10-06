package com.orazaka.studio.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PackTest {

  private static final Instant UPDATED = Instant.parse("2026-08-27T10:00:00Z");

  private static Pack pack(PackStatus status, List<String> studioKeys) {
    return new Pack(
        "realestate-studio",
        "business",
        "Studio Immobilier",
        "Vos biens deviennent des Reels.",
        null,
        "studio",
        null,
        RegulatoryClass.STANDARD,
        PackKind.VERTICAL,
        status,
        100,
        studioKeys,
        Set.of("fr", "en"),
        UPDATED);
  }

  @Test
  void accepts_aPublishedPackThatBundlesAStudio() {
    Pack pack = pack(PackStatus.PUBLISHED, List.of("realestate-reels"));

    assertEquals("realestate-studio", pack.packKey());
    assertEquals("business", pack.categoryKey());
    assertTrue(pack.bundles("realestate-reels"));
    assertFalse(pack.bundles("trade-showcase"));
  }

  @Test
  void rejects_aPublishedPackThatBundlesNothing_invariantOne() {
    // An empty shelf item is a support ticket: the user pays and receives nothing. The check
    // lives here rather than in a service guard so no second caller can construct one (ERR-106).
    assertThrows(IllegalArgumentException.class, () -> pack(PackStatus.PUBLISHED, List.of()));
    assertThrows(IllegalArgumentException.class, () -> pack(PackStatus.PUBLISHED, null));
  }

  @Test
  void allowsAnEmptyDraft_becauseAPackIsAuthoredBeforeItIsFilled() {
    assertEquals(List.of(), pack(PackStatus.DRAFT, List.of()).studioKeys());
    assertEquals(List.of(), pack(PackStatus.WITHDRAWN, null).studioKeys());
  }

  @Test
  void defensivelyCopiesTheBundle_soACallerCannotEmptyAPublishedPackAfterConstruction() {
    List<String> mutable = new ArrayList<>(List.of("realestate-reels"));
    Pack pack = pack(PackStatus.PUBLISHED, mutable);

    mutable.clear();

    assertEquals(List.of("realestate-reels"), pack.studioKeys());
    assertThrows(UnsupportedOperationException.class, () -> pack.studioKeys().add("other"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Realestate-Studio", "realestate studio", "1pack", "-pack", ""})
  void rejects_aKeyThatCannotBeAUrlSegment(String key) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Pack(
                key,
                "business",
                "Studio Immobilier",
                null,
                null,
                "studio",
                null,
                RegulatoryClass.STANDARD,
                PackKind.VERTICAL,
                PackStatus.DRAFT,
                0,
                List.of(),
                Set.of(),
                UPDATED));
  }

  @Test
  void rejects_aPackWithNoShelf_becauseTheMarketplaceHasNowhereToRenderIt() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Pack(
                "realestate-studio",
                " ",
                "Studio Immobilier",
                null,
                null,
                "studio",
                null,
                RegulatoryClass.STANDARD,
                PackKind.VERTICAL,
                PackStatus.DRAFT,
                0,
                List.of(),
                Set.of(),
                UPDATED));
  }

  @Test
  void rejects_aPackWithNoLabelOrIcon() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Pack(
                "realestate-studio",
                "business",
                null,
                null,
                null,
                "studio",
                null,
                RegulatoryClass.STANDARD,
                PackKind.VERTICAL,
                PackStatus.DRAFT,
                0,
                List.of(),
                Set.of(),
                UPDATED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Pack(
                "realestate-studio",
                "business",
                "Studio Immobilier",
                null,
                null,
                " ",
                null,
                RegulatoryClass.STANDARD,
                PackKind.VERTICAL,
                PackStatus.DRAFT,
                0,
                List.of(),
                Set.of(),
                UPDATED));
  }

  @Test
  void rejects_aPackWithNoRegulatoryClassKindOrStatus_becauseNoneHasASafeDefaultHere() {
    assertThrows(
        NullPointerException.class,
        () ->
            new Pack(
                "realestate-studio",
                "business",
                "Studio Immobilier",
                null,
                null,
                "studio",
                null,
                null,
                PackKind.VERTICAL,
                PackStatus.DRAFT,
                0,
                List.of(),
                Set.of(),
                UPDATED));
    assertThrows(
        NullPointerException.class,
        () ->
            new Pack(
                "realestate-studio",
                "business",
                "Studio Immobilier",
                null,
                null,
                "studio",
                null,
                RegulatoryClass.STANDARD,
                null,
                PackStatus.DRAFT,
                0,
                List.of(),
                Set.of(),
                UPDATED));
    assertThrows(
        NullPointerException.class,
        () ->
            new Pack(
                "realestate-studio",
                "business",
                "Studio Immobilier",
                null,
                null,
                "studio",
                null,
                RegulatoryClass.STANDARD,
                PackKind.VERTICAL,
                null,
                0,
                List.of(),
                Set.of(),
                UPDATED));
  }

  private static Pack classified(RegulatoryClass regulatoryClass, PackKind kind) {
    return new Pack(
        "media-toolkit",
        "business",
        "Media",
        null,
        null,
        "image",
        null,
        regulatoryClass,
        kind,
        PackStatus.PUBLISHED,
        0,
        List.of("image-generation"),
        Set.of("fr"),
        UPDATED);
  }

  @Test
  void rejects_aRegulatedToolkit_becauseConsentLivesOnAnInstallationAndAToolkitHasNone() {
    // ck_pack_toolkit_not_regulated, mirrored. The kind does not imply a class — this is the one
    // combination that has nowhere to record what REGULATED requires (ADR-061).
    IllegalArgumentException refused =
        assertThrows(
            IllegalArgumentException.class,
            () -> classified(RegulatoryClass.REGULATED, PackKind.TOOLKIT));
    assertTrue(refused.getMessage().contains("TOOLKIT"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"STANDARD", "SENSITIVE"})
  void accepts_aToolkitOfEveryOtherClass_becauseKindAndClassAreOrthogonal(String regulatoryClass) {
    // SENSITIVE's four run controls hang off the pack and the run, not the installation, so a
    // SENSITIVE toolkit loses none of them. A reader who assumes TOOLKIT implies STANDARD is wrong.
    Pack pack = classified(RegulatoryClass.valueOf(regulatoryClass), PackKind.TOOLKIT);

    assertEquals(PackKind.TOOLKIT, pack.kind());
  }

  @Test
  void accepts_aRegulatedVertical_becauseItsInstallationIsWhereConsentIsRecorded() {
    assertEquals(
        RegulatoryClass.REGULATED,
        classified(RegulatoryClass.REGULATED, PackKind.VERTICAL).regulatoryClass());
  }
}
