package com.krizaka.orazaka.studio.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class StudioStatusTest {

  @Test
  void pinsTheVocabulary_becauseItIsWrittenVerbatimIntoStudioStatus() {
    assertEquals(
        List.of("DRAFT", "IN_REVIEW", "PUBLISHED", "DEPRECATED", "WITHDRAWN"),
        Arrays.stream(StudioStatus.values()).map(Enum::name).toList());
  }
}
