package com.krizaka.orazaka.studio.domain.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BlueprintValidationExceptionTest {

  @Test
  void carriesTheOffendingStep_soTheAuthoringUiCanPointAtTheNode() {
    BlueprintValidationException thrown =
        new BlueprintValidationException("describe", "dependsOn names an unknown step: brief");

    assertEquals("describe", thrown.stepId());
    assertTrue(thrown.getMessage().contains("describe"));
    assertTrue(thrown.getMessage().contains("brief"));
  }

  @Test
  void carriesNoStep_whenTheViolationBelongsToTheGraphRatherThanOneNode() {
    BlueprintValidationException thrown =
        new BlueprintValidationException("the step graph is cyclic, involving: [a, b]");

    assertNull(thrown.stepId());
    assertEquals("the step graph is cyclic, involving: [a, b]", thrown.getMessage());
  }
}
