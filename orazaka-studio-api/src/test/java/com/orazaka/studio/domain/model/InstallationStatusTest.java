package com.orazaka.studio.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class InstallationStatusTest {

  @Test
  void pinsTheVocabulary_becauseItIsWrittenVerbatimIntoStudioInstallationStatus() {
    assertEquals(
        List.of("ACTIVE", "PAUSED", "UPGRADE_AVAILABLE", "REVOKED"),
        Arrays.stream(InstallationStatus.values()).map(Enum::name).toList());
  }
}
