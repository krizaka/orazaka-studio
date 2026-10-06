package com.orazaka.studio.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class StudioPricingTest {

  @Test
  void pinsTheVocabulary_becauseItIsWrittenVerbatimIntoStudioPricingAndItsCheckConstraint() {
    assertEquals(
        List.of("FREE", "INCLUDED", "PAID"),
        Arrays.stream(StudioPricing.values()).map(Enum::name).toList());
  }
}
