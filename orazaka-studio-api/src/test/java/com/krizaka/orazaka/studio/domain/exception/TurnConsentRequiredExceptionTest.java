package com.krizaka.orazaka.studio.domain.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TurnConsentRequiredExceptionTest {

  @Test
  void carries_theVersionAndTheStatement_verbatim() {
    TurnConsentRequiredException refused = new TurnConsentRequiredException("1.1", "Ce Studio…");

    assertEquals("1.1", refused.requiredVersion());
    assertEquals("Ce Studio…", refused.statement());
    assertTrue(refused.getMessage().contains("1.1"));
  }
}
