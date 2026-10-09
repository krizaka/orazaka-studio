package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PackSummaryResponseTest {

  @Test
  @DisplayName("A card carries its shelf, its icon and its bundle — everything it renders")
  void cardCarriesWhatItRenders() {
    PackSummaryResponse response =
        PackSummaryResponse.from(
            PackFixtures.priced(),
            com.krizaka.orazaka.studioservice.domain.model.PackAccess.BUYABLE);

    assertThat(response.packKey()).isEqualTo("realestate-studio");
    assertThat(response.categoryKey()).isEqualTo("business");
    assertThat(response.label()).isEqualTo("Studio Immobilier");
    assertThat(response.iconKey()).isEqualTo("studio");
    assertThat(response.studioKeys()).isEqualTo(List.of("realestate-reels"));
    assertThat(response.regulatoryClass()).isEqualTo("STANDARD");
    assertThat(response.priceCents()).isEqualTo(4900);
    assertThat(response.includedCredits()).isEqualTo(5000L);
  }

  @Test
  @DisplayName("An unpriced card sends null, never zero — the UI must render \"—\", not \"free\"")
  void unpricedCardSendsNullNotZero() {
    // Collapsing absent into 0 would have the marketplace advertise a price the product never
    // agreed to, every time the billing service restarts.
    PackSummaryResponse response =
        PackSummaryResponse.from(
            PackFixtures.unpriced(),
            com.krizaka.orazaka.studioservice.domain.model.PackAccess.BUYABLE);

    assertThat(response.priceCents()).isNull();
    assertThat(response.includedCredits()).isNull();
    assertThat(response.label()).isEqualTo("Studio Immobilier");
  }
}
