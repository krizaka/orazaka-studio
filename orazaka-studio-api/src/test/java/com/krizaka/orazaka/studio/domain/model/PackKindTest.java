package com.krizaka.orazaka.studio.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class PackKindTest {

  @Test
  void pinsTheVocabulary_becauseItIsWrittenVerbatimIntoTheCkPackKindCheck() {
    assertEquals(
        List.of("VERTICAL", "TOOLKIT"), Arrays.stream(PackKind.values()).map(Enum::name).toList());
  }

  @Test
  void verticalComesFirst_becauseItIsTheColumnDefaultAndTheKindThatGrantsNothingByItself() {
    // Mirrors `kind VARCHAR(20) NOT NULL DEFAULT 'VERTICAL'` in 80-studio.sql. Every pack that
    // existed before this enum is a VERTICAL, and a VERTICAL still needs an installation and an
    // entitlement: defaulting the other way would hand out capability nobody decided to hand out.
    assertEquals(PackKind.VERTICAL, PackKind.values()[0]);
  }
}
