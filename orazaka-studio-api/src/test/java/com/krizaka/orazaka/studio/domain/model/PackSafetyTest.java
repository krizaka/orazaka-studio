package com.krizaka.orazaka.studio.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PackSafetyTest {

  private static final CrisisResource QC =
      new CrisisResource(
          "CA-QC",
          "Ligne québécoise de prévention du suicide",
          "1 866 APPELLE (277-3553)",
          "24/7",
          "https://www.quebec.ca/",
          LocalDate.of(2026, 10, 11));

  @Test
  void carries_theConversationalPrompt_whenThePackWritesOne() {
    PackSafety safety =
        new PackSafety(
            List.of("en finir"),
            "Je m'arrête là.",
            "review",
            Map.of("CA-QC", QC),
            "  Je suis une IA. ");

    assertEquals("Je suis une IA.", safety.systemPrompt());
  }

  @Test
  void reads_aBlankPrompt_asNone() {
    // An empty system message would put nothing ahead of a caller's context while reading as if
    // something were there.
    assertNull(
        new PackSafety(List.of("en finir"), "r", "review", Map.of("CA-QC", QC), " ")
            .systemPrompt());
    assertNull(
        new PackSafety(List.of("en finir"), "r", "review", Map.of("CA-QC", QC), null)
            .systemPrompt());
  }

  @Test
  void answers_theRegionsLine_withTheReviewedText() {
    PackSafety safety =
        new PackSafety(List.of("en finir"), "Je m'arrête là.", "review", Map.of("CA-QC", QC), null);

    assertEquals("Je m'arrête là.\n\n" + QC.render(), safety.responseFor("CA-QC"));
    assertNull(safety.responseFor("FR"));
  }

  @Test
  void still_refuses_aDeclarationWithNoResource() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new PackSafety(List.of("en finir"), "r", "review", Map.of(), "prompt"));
  }
}
