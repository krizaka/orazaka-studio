package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.orazaka.studio.domain.model.PackCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PackCategoryResponseTest {

  @Test
  @DisplayName("A shelf arrives already named, which is why it stopped being a CHECK constraint")
  void shelfArrivesNamed() {
    PackCategoryResponse response = PackCategoryResponse.from(PackFixtures.BUSINESS);

    assertThat(response.categoryKey()).isEqualTo("business");
    assertThat(response.label()).isEqualTo("Business");
    assertThat(response.iconKey()).isEqualTo("briefcase");
    assertThat(response.sortWeight()).isEqualTo(100);
  }

  @Test
  @DisplayName("A shelf whose locale carries no description sends null rather than an empty string")
  void missingDescriptionStaysNull() {
    PackCategoryResponse response =
        PackCategoryResponse.from(
            new PackCategory("lifestyle", "Life Style", null, "heart", 90, true));

    assertThat(response.description()).isNull();
    assertThat(response.label()).isEqualTo("Life Style");
  }
}
