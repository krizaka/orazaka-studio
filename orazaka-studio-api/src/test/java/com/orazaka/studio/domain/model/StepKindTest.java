package com.orazaka.studio.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class StepKindTest {

  @Test
  void pinsTheVocabulary_becauseBlueprintJsonNamesTheseKindsAndAddingOneIsHowTheDslGrows() {
    assertEquals(
        List.of("CAPABILITY", "KNOWLEDGE", "CONNECTOR", "APPROVAL", "TRANSFORM"),
        Arrays.stream(StepKind.values()).map(Enum::name).toList());
  }
}
