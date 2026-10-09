package com.krizaka.orazaka.studio.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class PackStatusTest {

  @Test
  void pinsTheVocabulary_becauseItIsWrittenVerbatimIntoTheCkPackStatusCheck() {
    assertEquals(
        List.of("DRAFT", "PUBLISHED", "WITHDRAWN"),
        Arrays.stream(PackStatus.values()).map(Enum::name).toList());
  }
}
