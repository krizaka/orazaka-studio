package com.krizaka.orazaka.studio.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TurnDeclarationsTest {

  private static final UUID ID = UUID.randomUUID();
  private static final CrisisResource QC =
      new CrisisResource(
          "CA-QC",
          "Ligne québécoise de prévention du suicide",
          "1 866 APPELLE (277-3553)",
          "24/7",
          "https://www.quebec.ca/",
          LocalDate.of(2026, 10, 11));

  @Test
  void assembles_theCrisisReply_asTheStudioPathDoes() {
    TurnDeclarations declared =
        new TurnDeclarations(
            ID,
            "session-summary",
            "bien-etre",
            "CA-QC",
            null,
            List.of("en finir"),
            "Je m'arrête là.",
            QC,
            "Je suis une IA.");

    assertTrue(declared.guardsCrisis());
    assertEquals("Je m'arrête là.\n\n" + QC.render(), declared.crisisReply());
  }

  @Test
  void a_packThatDeclaresNothing_guardsNothing() {
    TurnDeclarations declared =
        new TurnDeclarations(ID, "s", "p", null, null, null, null, null, " ");

    assertFalse(declared.guardsCrisis());
    assertNull(declared.crisisReply());
    assertNull(declared.systemPrompt());
  }

  @Test
  void refuses_halfACrisisDeclaration() {
    // Terms with no reply short-circuit a person into silence; a reply with no line points nowhere.
    assertThrows(
        IllegalArgumentException.class,
        () -> new TurnDeclarations(ID, "s", "p", "CA-QC", null, List.of("x"), null, QC, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TurnDeclarations(ID, "s", "p", "CA-QC", null, List.of("x"), "r", null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TurnDeclarations(ID, "s", "p", "CA-QC", null, List.of(), "r", QC, null));
  }

  @Test
  void requires_theInstallationAndTheStudio() {
    assertThrows(
        NullPointerException.class,
        () -> new TurnDeclarations(null, "s", "p", null, null, null, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TurnDeclarations(ID, " ", "p", null, null, null, null, null, null));
  }
}
