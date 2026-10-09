package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.orazaka.studioservice.domain.model.StudioUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StudioUsageResponseTest {

  @Test
  @DisplayName("The success rate is computed server-side, not left to each client to derive")
  void carriesTheComputedRate() {
    var response = StudioUsageResponse.from(new StudioUsage("k", "L", 4, 10, 8, 2, 80));

    assertThat(response.successRate()).isEqualTo(0.8d);
    assertThat(response.installedButUnused()).isFalse();
  }

  @Test
  @DisplayName("The churn signal travels with the row")
  void carriesTheChurnSignal() {
    var response = StudioUsageResponse.from(new StudioUsage("k", "L", 5, 0, 0, 0, 80));

    assertThat(response.installedButUnused()).isTrue();
  }
}
