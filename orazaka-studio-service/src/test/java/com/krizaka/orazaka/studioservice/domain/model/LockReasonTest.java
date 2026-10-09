package com.krizaka.orazaka.studioservice.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LockReasonTest {

  @Test
  @DisplayName("Pins the vocabulary the upsell copy and the client both switch on")
  void pinsTheVocabulary() {
    assertThat(Arrays.stream(LockReason.values()).map(Enum::name).toList())
        .isEqualTo(List.of("NONE", "REQUIRES_PURCHASE", "REQUIRES_PLAN", "UNKNOWN"));
  }
}
