package com.orazaka.studio.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class RegulatoryClassTest {

  @Test
  void pinsTheVocabulary_becauseItIsWrittenVerbatimIntoTheCkPackRegulatoryCheck() {
    assertEquals(
        List.of("STANDARD", "SENSITIVE", "REGULATED"),
        Arrays.stream(RegulatoryClass.values()).map(Enum::name).toList());
  }

  @Test
  void defaultsToStandard_becauseEveryPackShippedSoFarCarriesNoRegulatoryWeight() {
    // Mirrors `regulatory_class VARCHAR(20) NOT NULL DEFAULT 'STANDARD'` in 80-studio.sql.
    // A default of SENSITIVE would switch controls on for packs that need none; a nullable
    // column would make "unclassified" a fourth state nobody wrote a rule for.
    assertEquals(RegulatoryClass.STANDARD, RegulatoryClass.values()[0]);
  }
}
