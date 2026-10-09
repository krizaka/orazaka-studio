package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.orazaka.studio.domain.model.PackKind;
import com.krizaka.orazaka.studio.domain.model.Studio;
import com.krizaka.orazaka.studio.domain.model.StudioPricing;
import com.krizaka.orazaka.studio.domain.model.StudioStatus;
import com.krizaka.orazaka.studioservice.domain.model.StudioAccess;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StudioDetailResponseTest {

  private static final Studio STUDIO =
      new Studio(
          "trade-showcase",
          "Vitrine Artisan",
          "Vos chantiers deviennent une vitrine.",
          "trades",
          "studio",
          null,
          StudioPricing.FREE,
          null,
          PackKind.VERTICAL,
          "studio.trade-showcase",
          StudioStatus.PUBLISHED,
          "orazaka",
          "1.0.0",
          Set.of("fr", "en"),
          Instant.parse("2026-08-05T10:00:00Z"));

  @Test
  @DisplayName("Schemas travel as raw JSON so the client generates its own forms")
  void carriesRawSchemas() {
    var response =
        StudioDetailResponse.from(
            STUDIO,
            "Longue copie",
            80,
            "{\"type\":\"object\"}",
            "{\"tone\":{}}",
            StudioAccess.open());

    assertThat(response.inputSchema()).isEqualTo("{\"type\":\"object\"}");
    assertThat(response.configSchema()).isEqualTo("{\"tone\":{}}");
    assertThat(response.estimatedCredits()).isEqualTo(80);
    assertThat(response.status()).isEqualTo("PUBLISHED");
  }

  @Test
  @DisplayName("An unpublished studio detail is well-formed with no schemas")
  void unpublishedIsWellFormed() {
    var response = StudioDetailResponse.from(STUDIO, null, 0, null, null, StudioAccess.open());

    assertThat(response.inputSchema()).isNull();
    assertThat(response.description()).isNull();
    assertThat(response.estimatedCredits()).isZero();
  }
}
