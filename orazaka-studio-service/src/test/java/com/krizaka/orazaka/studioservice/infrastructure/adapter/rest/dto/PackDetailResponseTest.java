package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PackDetailResponseTest {

  @Test
  @DisplayName("The detail resolves its own shelf, so it cannot disagree with the card's heading")
  void detailCarriesItsShelfInline() {
    PackDetailResponse response =
        PackDetailResponse.from(
            PackFixtures.priced(),
            com.krizaka.orazaka.studioservice.domain.model.PackAccess.BUYABLE);

    assertThat(response.category().label()).isEqualTo("Business");
    assertThat(response.description()).isEqualTo("Le pack métier de l'agent immobilier.");
    assertThat(response.status()).isEqualTo("PUBLISHED");
    assertThat(response.studioKeys()).isEqualTo(List.of("realestate-reels"));
  }

  @Test
  @DisplayName("Locales are sorted, so a client can diff two responses without sorting them first")
  void localesAreOrdered() {
    // The record holds a Set; an unordered wire payload would make the response non-deterministic
    // and any caching or ETag on it useless.
    assertThat(
            PackDetailResponse.from(
                    PackFixtures.priced(),
                    com.krizaka.orazaka.studioservice.domain.model.PackAccess.BUYABLE)
                .locales())
        .isEqualTo(List.of("en", "fr"));
  }

  @Test
  @DisplayName("An unpriced detail degrades the same way a card does")
  void unpricedDetailSendsNull() {
    PackDetailResponse response =
        PackDetailResponse.from(
            PackFixtures.unpriced(),
            com.krizaka.orazaka.studioservice.domain.model.PackAccess.BUYABLE);

    assertThat(response.priceCents()).isNull();
    assertThat(response.includedCredits()).isNull();
  }
}
