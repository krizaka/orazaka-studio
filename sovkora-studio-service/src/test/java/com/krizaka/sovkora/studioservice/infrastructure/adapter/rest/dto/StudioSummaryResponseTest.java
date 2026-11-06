package com.krizaka.sovkora.studioservice.infrastructure.adapter.rest.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.sovkora.studio.domain.model.PackKind;
import com.krizaka.sovkora.studio.domain.model.Studio;
import com.krizaka.sovkora.studio.domain.model.StudioPricing;
import com.krizaka.sovkora.studio.domain.model.StudioStatus;
import com.krizaka.sovkora.studioservice.domain.model.LockReason;
import com.krizaka.sovkora.studioservice.domain.model.StudioAccess;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StudioSummaryResponseTest {

  private static Studio studio(StudioPricing pricing, String packKey) {
    return new Studio(
        "realestate-reels",
        "Reels Immobilier",
        "Cinq photos deviennent un Reel.",
        "real-estate",
        "studio",
        "asset-1",
        pricing,
        packKey,
        PackKind.VERTICAL,
        "studio.realestate-reels",
        StudioStatus.PUBLISHED,
        "sovkora",
        "1.0.0",
        Set.of("fr"),
        Instant.parse("2026-08-05T10:00:00Z"));
  }

  @Test
  @DisplayName("A locked card still carries the pack, so the UI can render a buy button")
  void lockedCardCarriesThePackage() {
    var response =
        StudioSummaryResponse.from(
            studio(StudioPricing.PAID, "realestate-studio"),
            new StudioAccess(true, LockReason.REQUIRES_PURCHASE, "realestate-studio"));

    assertThat(response.locked()).isTrue();
    assertThat(response.lockedReason()).isEqualTo(LockReason.REQUIRES_PURCHASE);
    assertThat(response.packKey()).isEqualTo("realestate-studio");
    assertThat(response.pricing()).isEqualTo("PAID");
  }

  @Test
  @DisplayName("An open card reports no lock reason")
  void openCardHasNoReason() {
    var response =
        StudioSummaryResponse.from(studio(StudioPricing.FREE, null), StudioAccess.open());

    assertThat(response.locked()).isFalse();
    assertThat(response.lockedReason()).isEqualTo(LockReason.NONE);
    assertThat(response.label()).isEqualTo("Reels Immobilier");
  }
}
