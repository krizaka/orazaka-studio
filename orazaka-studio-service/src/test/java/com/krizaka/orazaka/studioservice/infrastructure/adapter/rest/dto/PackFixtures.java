package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

import com.krizaka.orazaka.studio.domain.model.Pack;
import com.krizaka.orazaka.studio.domain.model.PackCategory;
import com.krizaka.orazaka.studio.domain.model.PackKind;
import com.krizaka.orazaka.studio.domain.model.PackStatus;
import com.krizaka.orazaka.studio.domain.model.PackSummary;
import com.krizaka.orazaka.studio.domain.model.RegulatoryClass;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * The one catalogue row the DTO tests project, built once.
 *
 * <p>A test fixture rather than a copy per test file: three DTOs project the same {@link
 * PackSummary}, and three hand-written copies of a thirteen-component record would drift into three
 * subtly different Packs, which is exactly the failure the DTO tests exist to catch.
 */
final class PackFixtures {

  static final PackCategory BUSINESS =
      new PackCategory(
          "business", "Business", "Les packs qui font le travail.", "briefcase", 100, true);

  private PackFixtures() {}

  static Pack pack() {
    return new Pack(
        "realestate-studio",
        "business",
        "Studio Immobilier",
        "Vos biens deviennent des Reels.",
        "Le pack métier de l'agent immobilier.",
        "studio",
        "asset-1",
        RegulatoryClass.STANDARD,
        PackKind.VERTICAL,
        PackStatus.PUBLISHED,
        100,
        List.of("realestate-reels"),
        Set.of("fr", "en"),
        Instant.parse("2026-08-27T10:00:00Z"));
  }

  static PackSummary priced() {
    return new PackSummary(pack(), BUSINESS, 4900, 5000L);
  }

  static PackSummary unpriced() {
    return new PackSummary(pack(), BUSINESS, null, null);
  }
}
