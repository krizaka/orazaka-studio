package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.orazaka.studio.domain.model.BlueprintStatus;
import com.krizaka.orazaka.studioservice.domain.model.BlueprintVersion;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BlueprintVersionResponseTest {

  @Test
  @DisplayName("Projects a version with its changelog and cost")
  void projectsVersion() {
    var response =
        BlueprintVersionResponse.from(
            new BlueprintVersion(
                "trade-showcase",
                "1.0.0",
                BlueprintStatus.PUBLISHED,
                80,
                "Vitrine initiale",
                Instant.parse("2026-08-05T10:00:00Z")));

    assertThat(response.version()).isEqualTo("1.0.0");
    assertThat(response.status()).isEqualTo("PUBLISHED");
    assertThat(response.estimatedCredits()).isEqualTo(80);
    assertThat(response.changelog()).isEqualTo("Vitrine initiale");
  }
}
