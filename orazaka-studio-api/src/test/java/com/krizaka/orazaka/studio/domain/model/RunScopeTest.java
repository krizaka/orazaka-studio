package com.krizaka.orazaka.studio.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RunScopeTest {

  private static final RunScope SCOPE =
      new RunScope(
          Map.of("photos", java.util.List.of("a1", "a2"), "trade", "plombier"),
          Map.of("tone", "premium", "brandName", "Dupont & Fils"),
          Map.of(
              "photoDescriptions",
              "cuisine refaite",
              "script",
              Map.of("text", "bonjour", "caption", "légende")),
          "item-7",
          Map.of("instagramToken", "s3cr3t"));

  // ── The supported grammar (design §6.2) ───────────────────────────────────

  @Test
  void resolves_anInput() {
    assertEquals("plombier", SCOPE.resolve("{{inputs.trade}}"));
  }

  @Test
  void resolves_aConfigValue() {
    assertEquals("Ton: premium", SCOPE.resolve("Ton: {{config.tone}}"));
  }

  @Test
  void resolves_theWholeConfig_becauseTheDslPassesABrandKitAsOneValue() {
    String rendered = SCOPE.resolve("{{config}}");

    // Readable pairs, not a Java map dump: this string goes into a model's prompt.
    assertTrue(rendered.contains("tone: premium"));
    assertFalse(rendered.contains("{"));
  }

  @Test
  void resolves_aBareStepOutput() {
    assertEquals("cuisine refaite", SCOPE.resolve("{{steps.photoDescriptions}}"));
  }

  @Test
  void resolves_aFieldOfAStructuredStepOutput() {
    assertEquals("bonjour", SCOPE.resolve("{{steps.script.text}}"));
  }

  @Test
  void resolves_theForEachItem() {
    assertEquals("item-7", SCOPE.resolve("{{item}}"));
  }

  @Test
  void resolves_aSecret_becauseConnectorStepsNeedIt() {
    assertEquals("s3cr3t", SCOPE.resolve("{{secrets.instagramToken}}"));
  }

  @Test
  void resolves_severalPlaceholdersInOneTemplate() {
    assertEquals(
        "Dupont & Fils / plombier", SCOPE.resolve("{{config.brandName}} / {{inputs.trade}}"));
  }

  @Test
  void appliesTheDefault_whenTheValueIsAbsent() {
    assertEquals("alloy", SCOPE.resolve("{{config.voice:alloy}}"));
  }

  @Test
  void prefersTheValueOverTheDefault() {
    assertEquals("premium", SCOPE.resolve("{{config.tone:chaleureux}}"));
  }

  @Test
  void rendersEmpty_whenAnOptionalValueIsAbsentAndDeclaresNoDefault() {
    // An optional input that was not supplied is not a malformed template.
    assertEquals("", SCOPE.resolve("{{inputs.clip}}"));
  }

  @Test
  void leavesATemplateWithoutPlaceholdersUntouched() {
    assertEquals("no placeholder here", SCOPE.resolve("no placeholder here"));
  }

  // ── Everything the grammar refuses (the security boundary, ADR-034 §1) ────

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{{ System.exit(0) }}",
        "{{ 1 + 1 }}",
        "{{inputs}}",
        "{{steps}}",
        "{{unknownRoot.x}}",
        "{{inputs.a.b}}",
        "{{steps.a.b.c}}",
        "{{}}",
        "${inputs.trade}"
      })
  void refuses_anythingOutsideTheGrammar_ratherThanRenderingEmpty(String template) {
    // "${inputs.trade}" is the capability payload_template grammar, not this one: it contains no
    // {{…}}, so it renders verbatim rather than resolving — which is the assertion below.
    if (template.startsWith("${")) {
      assertEquals(template, SCOPE.resolve(template));
      return;
    }
    assertThrows(IllegalArgumentException.class, () -> SCOPE.resolve(template));
  }

  @Test
  void refuses_aNullTemplate() {
    assertThrows(NullPointerException.class, () -> SCOPE.resolve(null));
  }

  // ── Conditions — the four supported forms and nothing else ────────────────

  @Test
  void condition_equalsALiteral() {
    assertTrue(SCOPE.matches("{{config.tone}} == 'premium'"));
    assertFalse(SCOPE.matches("{{config.tone}} == 'direct'"));
  }

  @Test
  void condition_notEqualsALiteral() {
    assertTrue(SCOPE.matches("{{config.tone}} != 'direct'"));
    assertFalse(SCOPE.matches("{{config.tone}} != 'premium'"));
  }

  @Test
  void condition_isNull_holdsForAnAbsentValue() {
    assertTrue(SCOPE.matches("{{inputs.clip}} is null"));
    assertFalse(SCOPE.matches("{{inputs.trade}} is null"));
  }

  @Test
  void condition_isNotNull() {
    assertTrue(SCOPE.matches("{{inputs.trade}} is not null"));
    assertFalse(SCOPE.matches("{{inputs.clip}} is not null"));
  }

  @Test
  void condition_comparesAgainstTheNullLiteral_asTheDslWritesIt() {
    assertTrue(SCOPE.matches("{{inputs.clip}} == null"));
    assertFalse(SCOPE.matches("{{inputs.trade}} == null"));
  }

  @Test
  void condition_readsABooleanThroughItsDefault() {
    // The DSL's optional-publish gate: the key is absent, so the default decides.
    assertFalse(SCOPE.matches("{{config.autoPublish:false}} == true"));
    assertTrue(SCOPE.matches("{{config.autoPublish:true}} == true"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{{config.tone}} =~ /premium/",
        "{{config.tone}} && {{inputs.trade}}",
        "config.tone == 'premium'",
        "{{config.tone}} > 3",
        "true",
        ""
      })
  void refuses_anyConditionOutsideTheFourSupportedForms(String condition) {
    assertThrows(IllegalArgumentException.class, () -> SCOPE.matches(condition));
  }

  @Test
  void refuses_aNullCondition() {
    assertThrows(NullPointerException.class, () -> SCOPE.matches(null));
  }

  // ── assertValidGrammar — the check a blueprint runs without a scope ───────

  @Test
  void assertValidGrammar_acceptsEverySupportedForm() {
    RunScope.assertValidGrammar("{{item}} {{inputs.a}} {{config}} {{steps.a.b}} {{secrets.a:x}}");
  }

  @Test
  void assertValidGrammar_toleratesTheAbsenceOfATemplate() {
    RunScope.assertValidGrammar(null);
  }

  @Test
  void assertValidGrammar_rejectsAnUnsupportedForm() {
    assertThrows(
        IllegalArgumentException.class, () -> RunScope.assertValidGrammar("{{ eval('x') }}"));
  }

  // ── Secrecy and immutability ──────────────────────────────────────────────

  @Test
  void toString_neverContainsASecret() {
    String rendered = SCOPE.toString();

    assertFalse(rendered.contains("s3cr3t"));
    assertTrue(rendered.contains("<redacted:1>"));
    assertTrue(rendered.contains("premium"));
  }

  @Test
  void copiesEveryMap_soAMutationOfTheSourceCannotChangeARunningDag() {
    Map<String, Object> source = new HashMap<>(Map.of("trade", "plombier"));
    RunScope scope = new RunScope(source, Map.of(), Map.of(), null, Map.of());

    source.put("trade", "électricien");

    assertEquals("plombier", scope.resolve("{{inputs.trade}}"));
  }

  @Test
  void treatsNullMapsAsEmpty_soAScopeIsNeverPartlyConstructed() {
    RunScope scope = new RunScope(null, null, null, null, null);

    assertEquals("", scope.resolve("{{inputs.anything}}"));
    assertTrue(scope.secrets().isEmpty());
  }

  // ── Fan-out (withItem / expand) ───────────────────────────────────────────

  @Test
  void expand_yieldsTheRawListRatherThanItsRendering() {
    // A fan-out iterates asset ids, not a string that happens to look like a list.
    assertEquals(java.util.List.of("a1", "a2"), SCOPE.expand("{{inputs.photos}}"));
  }

  @Test
  void expand_yieldsOneItemForAScalar_soAForEachOverASingleValueStillRuns() {
    assertEquals(java.util.List.of("plombier"), SCOPE.expand("{{inputs.trade}}"));
  }

  @Test
  void expand_yieldsNothingForAnAbsentSource() {
    assertTrue(SCOPE.expand("{{inputs.missing}}").isEmpty());
  }

  @Test
  void expand_refusesAnythingThatIsNotASinglePlaceholder() {
    assertThrows(IllegalArgumentException.class, () -> SCOPE.expand("photos: {{inputs.photos}}"));
    assertThrows(IllegalArgumentException.class, () -> SCOPE.expand("{{inputs.a}}{{inputs.b}}"));
  }

  @Test
  void withItem_bindsTheElementWithoutMutatingTheSharedScope() {
    // Every fan-out instance resolves concurrently; a shared mutable item would cross the wires.
    RunScope first = SCOPE.withItem("photo-1");
    RunScope second = SCOPE.withItem("photo-2");

    assertEquals("photo-1", first.resolve("{{item}}"));
    assertEquals("photo-2", second.resolve("{{item}}"));
    assertEquals("item-7", SCOPE.resolve("{{item}}"));
  }

  @Test
  void withItem_keepsEverythingElse() {
    RunScope bound = SCOPE.withItem("photo-1");

    assertEquals("premium", bound.resolve("{{config.tone}}"));
    assertFalse(bound.toString().contains("s3cr3t"));
  }

  // ── Rendering composite values (no Java syntax in a prompt) ───────────────

  @Test
  void rendersAFanOutOutputOnePerLine_notAsAJavaListDump() {
    RunScope scope =
        new RunScope(
            Map.of(),
            Map.of(),
            Map.of(
                "descriptions",
                java.util.List.of(
                    Map.of("content", "Cuisine refaite"), Map.of("content", "Salle de bain"))),
            null,
            Map.of());

    assertEquals("Cuisine refaite\nSalle de bain", scope.resolve("{{steps.descriptions}}"));
  }

  @Test
  void rendersASingleFieldStepOutputAsItsValue() {
    RunScope scope =
        new RunScope(
            Map.of(), Map.of(), Map.of("script", Map.of("content", "bonjour")), null, Map.of());

    assertEquals("bonjour", scope.resolve("{{steps.script}}"));
  }

  @Test
  void rendersAMultiFieldOutputAsReadablePairs_soTheAuthorSeesWhatToPick() {
    RunScope scope =
        new RunScope(
            Map.of(),
            Map.of(),
            Map.of("reel", new java.util.LinkedHashMap<>(Map.of("assetId", "a1"))),
            null,
            Map.of());

    // One entry still collapses; the point is that neither shape emits "{assetId=a1}".
    assertFalse(scope.resolve("{{steps.reel}}").contains("{"));
  }

  @Test
  void rendersAListOfScalarsOnePerLine() {
    RunScope scope =
        new RunScope(
            Map.of("photos", java.util.List.of("a1", "a2")), Map.of(), Map.of(), null, Map.of());

    assertEquals("a1\na2", scope.resolve("{{inputs.photos}}"));
  }

  /**
   * A step input that <i>is</i> a list must reach its executor as a list.
   *
   * <p>{@link RunScope#resolve} renders for prose — a fan-out's results, one per line — and that is
   * right for a prompt. It was also the only option every step input had, so a composition's {@code
   * photos} arrived as its ids joined by newlines, the media worker split on commas, found one
   * unusable id and failed with "compose requires at least one readable photo". Three layers were
   * fixed before this one, which is the layer that was wrong (ADR-046).
   */
  @Test
  void resolveValue_singlePlaceholderOverAList_keepsTheList() {
    RunScope scope =
        new RunScope(
            Map.of("photos", List.of("asset-a", "asset-b")), Map.of(), Map.of(), null, Map.of());

    assertEquals(List.of("asset-a", "asset-b"), scope.resolveValue("{{inputs.photos}}"));
  }

  @Test
  void resolveValue_placeholderInsideProse_staysProse() {
    RunScope scope =
        new RunScope(
            Map.of("photos", List.of("asset-a", "asset-b")), Map.of(), Map.of(), null, Map.of());

    assertEquals(
        "Les pièces: asset-a\nasset-b", scope.resolveValue("Les pièces: {{inputs.photos}}"));
  }

  @Test
  void resolveValue_scalarPlaceholder_isUnchangedFromResolve() {
    RunScope scope = new RunScope(Map.of("clip", "asset-c"), Map.of(), Map.of(), null, Map.of());

    assertEquals("asset-c", scope.resolveValue("{{inputs.clip}}"));
  }

  @Test
  void resolveValue_noPlaceholder_isTheLiteral() {
    RunScope scope = new RunScope(Map.of(), Map.of(), Map.of(), null, Map.of());

    assertEquals("9x16", scope.resolveValue("9x16"));
  }
}
