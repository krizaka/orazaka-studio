package com.krizaka.orazaka.studioservice.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RunArtefactTest {

  @Test
  void carriesTheDeclaredPresentation() {
    RunArtefact artefact = new RunArtefact("reel", "Votre Reel", "VIDEO", "asset-7");

    assertEquals("Votre Reel", artefact.label());
    assertEquals("VIDEO", artefact.type());
    assertEquals("asset-7", artefact.value());
  }

  @Test
  void fallsBackToTheKeyAndTextWhenTheBlueprintDeclaresNeither() {
    RunArtefact artefact = new RunArtefact("caption", null, null, "Une légende");

    assertEquals("caption", artefact.label(), "an unlabelled artefact is still nameable");
    assertEquals("TEXT", artefact.type(), "text is the safe rendering — it shows the value as-is");
  }

  @Test
  void treatsBlankLabelAndTypeAsAbsent() {
    RunArtefact artefact = new RunArtefact("caption", " ", " ", "x");

    assertEquals("caption", artefact.label());
    assertEquals("TEXT", artefact.type());
  }

  @Test
  void keepsAnEmptyValueRatherThanNull_soTheClientNeverGuards() {
    assertEquals("", new RunArtefact("caption", "L", "TEXT", null).value());
  }

  @Test
  void rejects_anArtefactWithNoKey() {
    assertThrows(NullPointerException.class, () -> new RunArtefact(null, "L", "TEXT", "v"));
    assertThrows(IllegalArgumentException.class, () -> new RunArtefact(" ", "L", "TEXT", "v"));
  }

  @Test
  void carriesItsLabelInEveryLanguageThePackWrote() {
    RunArtefact artefact =
        new RunArtefact(
            "image",
            "Votre image",
            "IMAGE",
            "asset-1",
            java.util.Map.of("en", "Your image", "fr", "Votre image"));

    assertEquals("Your image", artefact.labels().get("en"));
    assertEquals("Votre image", artefact.labels().get("fr"));
    assertEquals(
        java.util.Map.of(),
        new RunArtefact("k", "L", "TEXT", "v").labels(),
        "none written: an empty map, never null");
  }
}
