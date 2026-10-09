package com.krizaka.orazaka.studio.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunStatusTest {

  @Test
  void pinsTheVocabulary_becauseItIsWrittenVerbatimIntoStudioRunStatus() {
    assertEquals(
        List.of(
            "PENDING_APPROVAL",
            "RUNNING",
            "AWAITING_INPUT",
            "SUCCEEDED",
            "FAILED",
            "CANCELLED",
            "COMPENSATING"),
        Arrays.stream(RunStatus.values()).map(Enum::name).toList());
  }
}
