package com.krizaka.orazaka.studio.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlueprintStatusTest {

  @Test
  void pinsTheVocabulary_becauseTheImmutabilityTriggerComparesAgainstTheseLiterals() {
    assertEquals(
        List.of("DRAFT", "PUBLISHED", "DEPRECATED"),
        Arrays.stream(BlueprintStatus.values()).map(Enum::name).toList());
  }
}
