package com.krizaka.orazaka.studio.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BlueprintOutputTest {

  @Test
  void accepts_anOutputPointingAtAStepField() {
    BlueprintOutput output =
        new BlueprintOutput("reel", "Votre Reel", "{{steps.reel.assetId}}", "VIDEO");

    assertEquals("reel", output.key());
    assertEquals("VIDEO", output.type());
  }

  @Test
  void rejects_aBlankKeyLabelOrSource() {
    assertThrows(
        IllegalArgumentException.class, () -> new BlueprintOutput(" ", "L", "{{item}}", "TEXT"));
    assertThrows(
        IllegalArgumentException.class, () -> new BlueprintOutput("k", "", "{{item}}", "TEXT"));
    assertThrows(IllegalArgumentException.class, () -> new BlueprintOutput("k", "L", " ", "TEXT"));
    assertThrows(IllegalArgumentException.class, () -> new BlueprintOutput("k", "L", null, "TEXT"));
  }

  @Test
  void rejects_aNullType() {
    assertThrows(NullPointerException.class, () -> new BlueprintOutput("k", "L", "{{item}}", null));
  }

  @Test
  void rejects_anUngrammaticalSource_asTheOwnerOfThatField() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new BlueprintOutput("k", "L", "{{ eval('x') }}", "TEXT"));
  }
}
