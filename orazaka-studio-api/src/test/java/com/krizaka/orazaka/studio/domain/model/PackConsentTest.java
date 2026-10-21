package com.krizaka.orazaka.studio.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PackConsentTest {

  private static final PackConsent CONSENT =
      new PackConsent("2.0", "Ce Studio enregistre.", Map.of("en", "This Studio records."));

  @Test
  void theStatementIsServedInTheCallersLanguage_frenchBeingTheDefault() {
    assertEquals("This Studio records.", CONSENT.statementFor("en"));
    assertEquals("This Studio records.", CONSENT.statementFor("en-CA"));
    assertEquals("This Studio records.", CONSENT.statementFor("EN_us"));
    assertEquals("Ce Studio enregistre.", CONSENT.statementFor("fr"));
    assertEquals("Ce Studio enregistre.", CONSENT.statementFor("fr-CA"));
  }

  @Test
  void aLanguageThePackDoesNotShip_orNone_getsTheDefaultStatement() {
    assertEquals("Ce Studio enregistre.", CONSENT.statementFor("de"));
    assertEquals("Ce Studio enregistre.", CONSENT.statementFor(null));
    assertEquals("Ce Studio enregistre.", CONSENT.statementFor(""));
  }

  @Test
  void allStatementsListsEveryLanguage_theDefaultIncluded() {
    assertEquals(
        Map.of("fr", "Ce Studio enregistre.", "en", "This Studio records."),
        CONSENT.allStatements());
  }

  @Test
  void aConsentWithoutTranslations_isTheDefaultLanguageOnly() {
    PackConsent plain = new PackConsent("1.0", "J'accepte.");

    assertTrue(plain.statements().isEmpty());
    assertEquals("J'accepte.", plain.statementFor("en"));
  }

  @Test
  void aTranslationMustBeATwoLetterLanguageWithAText() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new PackConsent("1.0", "J'accepte.", Map.of("english", "I agree.")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PackConsent("1.0", "J'accepte.", Map.of("en", " ")));
  }
}
