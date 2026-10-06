package com.orazaka.studio.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ErrorPolicyTest {

  @Test
  void pinsTheVocabulary_becauseBlueprintJsonNamesThesePoliciesAndTheyDecideWhatIsBilled() {
    assertEquals(
        List.of("FAIL", "SKIP", "RETRY"),
        Arrays.stream(ErrorPolicy.values()).map(Enum::name).toList());
  }
}
