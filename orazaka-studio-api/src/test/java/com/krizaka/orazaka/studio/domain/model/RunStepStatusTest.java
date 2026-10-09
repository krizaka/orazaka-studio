package com.krizaka.orazaka.studio.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunStepStatusTest {

  @Test
  void pinsTheVocabulary_becauseTheSagaAdvancesAStepByComparingAgainstTheseLiterals() {
    assertEquals(
        List.of("PENDING", "RUNNING", "SUCCEEDED", "FAILED", "SKIPPED", "CANCELLED"),
        Arrays.stream(RunStepStatus.values()).map(Enum::name).toList());
  }
}
