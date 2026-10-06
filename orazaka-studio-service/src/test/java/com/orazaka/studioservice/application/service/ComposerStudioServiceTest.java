package com.orazaka.studioservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.orazaka.studio.domain.model.Blueprint;
import com.orazaka.studio.domain.model.BlueprintStatus;
import com.orazaka.studio.domain.model.BlueprintStep;
import com.orazaka.studio.domain.model.ErrorPolicy;
import com.orazaka.studio.domain.model.PackKind;
import com.orazaka.studio.domain.model.StepKind;
import com.orazaka.studio.domain.model.Studio;
import com.orazaka.studio.domain.model.StudioPricing;
import com.orazaka.studio.domain.model.StudioStatus;
import com.orazaka.studioservice.domain.model.ComposerStudio;
import com.orazaka.studioservice.domain.model.LockReason;
import com.orazaka.studioservice.domain.model.StudioAccess;
import com.orazaka.studioservice.domain.port.BlueprintRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Which Studios belong in a chat composer (ADR-068 §3).
 *
 * <p>Every case here is a <b>shape</b>, never a key: the same Studio key passes or fails depending
 * only on how many steps its blueprint has and how many things its schema asks for. That is the
 * property the predicate exists to have — {@code key.contains("image")} decided correctly for every
 * pack that existed when it was written and silently wrongly for the next one.
 */
class ComposerStudioServiceTest {

  private static final String ACTOR = "550e8400-e29b-41d4-a716-446655440002";

  private static final String ONE_TEXT_INPUT =
      """
      {"type":"object","required":["prompt"],
       "properties":{"prompt":{"type":"string","format":"prose"},
                     "size":{"type":"string","default":"1024x1024"},
                     "model":{"type":"string"}}}
      """;

  private static final String ONE_ASSET_INPUT =
      """
      {"type":"object","required":["assetId"],
       "properties":{"assetId":{"type":"string","format":"asset-id"},
                     "model":{"type":"string"}}}
      """;

  private static final String AN_ASSET_AND_A_QUESTION =
      """
      {"type":"object","required":["assetId"],
       "properties":{"assetId":{"type":"string","format":"asset-id"},
                     "prompt":{"type":"string","format":"prose","default":"Analyze this image"},
                     "model":{"type":"string"}}}
      """;

  private static final String TWO_REQUIRED_INPUTS =
      """
      {"type":"object","required":["prompt","tone"],
       "properties":{"prompt":{"type":"string","format":"prose"},
                     "tone":{"type":"string","format":"prose"}}}
      """;

  private static final String ONE_OBJECT_INPUT =
      """
      {"type":"object","required":["contact"],
       "properties":{"contact":{"type":"object"}}}
      """;

  /**
   * The shape the SENSITIVE pack ships: one step, one required string, and nothing that says what
   * it holds — a base64 identity document, which no chat bar can supply.
   */
  private static final String ONE_UNDECLARED_STRING =
      """
      {"type":"object","required":["documentBase64"],
       "properties":{"documentBase64":{"type":"string"},
                     "expectedIssuer":{"type":"string"}}}
      """;

  private static Studio studio(String key) {
    return new Studio(
        key,
        "Label of " + key,
        null,
        "general",
        "image",
        null,
        StudioPricing.INCLUDED,
        "a-pack",
        PackKind.TOOLKIT,
        "studio." + key,
        StudioStatus.PUBLISHED,
        "orazaka",
        "1.0.0",
        Set.of("fr"),
        Instant.now());
  }

  private static Blueprint blueprint(String key, String inputSchema, int steps) {
    List<BlueprintStep> graph =
        java.util.stream.IntStream.range(0, steps)
            .mapToObj(
                index ->
                    new BlueprintStep(
                        "step" + index,
                        StepKind.CAPABILITY,
                        "orazaka.core.media.image",
                        null,
                        Set.of(),
                        Map.of("prompt", "{{inputs.prompt}}"),
                        "out" + index,
                        null,
                        1,
                        ErrorPolicy.FAIL,
                        Map.of(),
                        1,
                        java.time.Duration.ofMinutes(5),
                        null))
            .toList();
    return new Blueprint(
        key,
        "1.0.0",
        BlueprintStatus.PUBLISHED,
        inputSchema,
        graph,
        List.of(),
        Map.of(),
        100,
        null,
        Instant.now());
  }

  /** A service over exactly these Studios, each with the blueprint given, all of them open. */
  private static ComposerStudioService serviceOver(Map<String, Blueprint> catalogue) {
    return serviceOver(catalogue, StudioAccess.open());
  }

  private static ComposerStudioService serviceOver(
      Map<String, Blueprint> catalogue, StudioAccess access) {
    StudioCatalogService catalogService = mock(StudioCatalogService.class);
    StudioAccessService accessService = mock(StudioAccessService.class);
    BlueprintRepository blueprints = mock(BlueprintRepository.class);

    List<Studio> studios =
        catalogue.keySet().stream().map(ComposerStudioServiceTest::studio).toList();
    when(catalogService.browse(any(), anyString())).thenReturn(studios);
    when(accessService.evaluateAll(any(), anyString()))
        .thenReturn(
            catalogue.keySet().stream()
                .collect(java.util.stream.Collectors.toMap(key -> key, key -> access)));
    catalogue.forEach(
        (key, blueprint) -> when(blueprints.find(key, "1.0.0")).thenReturn(Optional.of(blueprint)));

    return new ComposerStudioService(catalogService, accessService, blueprints, new ObjectMapper());
  }

  @Test
  @DisplayName("one step and one required string: the Studio belongs in the composer")
  void oneStepOneInputBelongs() {
    ComposerStudioService service =
        serviceOver(Map.of("a-studio", blueprint("a-studio", ONE_TEXT_INPUT, 1)));

    List<ComposerStudio> row = service.row("fr", ACTOR);

    assertThat(row)
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.studioKey()).isEqualTo("a-studio");
              assertThat(entry.label())
                  .as("the label comes from the Studio's i18n row")
                  .isEqualTo("Label of a-studio");
              assertThat(entry.iconKey()).as("and so does the icon").isEqualTo("image");
              assertThat(entry.capabilityKey())
                  .as("the capability its one step dispatches to, read off the blueprint")
                  .isEqualTo("orazaka.core.media.image");
              assertThat(entry.inputKey()).isEqualTo("prompt");
              assertThat(entry.inputKind()).isEqualTo(ComposerStudio.InputKind.TEXT);
              assertThat(entry.locked()).isFalse();
            });
  }

  @Test
  @DisplayName(
      "the optional inputs beside it do not disqualify it: the run is complete without them")
  void optionalInputsDoNotDisqualify() {
    // `size` is defaulted and `model` is resolved by the capability's catalogue when absent. A
    // predicate that demanded every property be defaulted would have removed the image button.
    assertThat(
            serviceOver(Map.of("a-studio", blueprint("a-studio", ONE_TEXT_INPUT, 1)))
                .row("fr", ACTOR))
        .hasSize(1);
  }

  @Test
  @DisplayName(
      "a second required input disqualifies it: a button has nowhere to ask for a second thing")
  void twoRequiredInputsDoNotBelong() {
    assertThat(
            serviceOver(Map.of("a-studio", blueprint("a-studio", TWO_REQUIRED_INPUTS, 1)))
                .row("fr", ACTOR))
        .isEmpty();
  }

  @Test
  @DisplayName("a second step disqualifies it, whatever its schema asks for")
  void aMultiStepStudioDoesNotBelong() {
    assertThat(
            serviceOver(Map.of("a-studio", blueprint("a-studio", ONE_TEXT_INPUT, 3)))
                .row("fr", ACTOR))
        .isEmpty();
  }

  @Test
  @DisplayName("an input that is not a string disqualifies it: that Studio needs its form")
  void aNonStringInputDoesNotBelong() {
    assertThat(
            serviceOver(Map.of("a-studio", blueprint("a-studio", ONE_OBJECT_INPUT, 1)))
                .row("fr", ACTOR))
        .isEmpty();
  }

  @Test
  @DisplayName("what the single input holds is declared: format asset-id makes it an attachment")
  void theAssetFormatIsDeclaredNotGuessed() {
    List<ComposerStudio> row =
        serviceOver(Map.of("an-analysis", blueprint("an-analysis", ONE_ASSET_INPUT, 1)))
            .row("fr", ACTOR);

    assertThat(row)
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.inputKey()).isEqualTo("assetId");
              assertThat(entry.inputKind())
                  .as("an opaque id and a sentence are both strings; the pack says which this is")
                  .isEqualTo(ComposerStudio.InputKind.ASSET);
              assertThat(entry.promptKey())
                  .as("no optional defaulted string: the schema offers nowhere for prose")
                  .isNull();
            });
  }

  @Test
  @DisplayName("an attachment Studio with a question field takes the prose the user typed too")
  void proseGoesWhereTheSchemaDeclaresADefault() {
    List<ComposerStudio> row =
        serviceOver(Map.of("an-analysis", blueprint("an-analysis", AN_ASSET_AND_A_QUESTION, 1)))
            .row("fr", ACTOR);

    // Read off the schema, not off the name: an optional string with a default is one the Studio
    // can run without and has an opinion about, which is exactly what typed prose replaces.
    // `model` declares no default and is never mistaken for it.
    assertThat(row)
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.inputKey()).isEqualTo("assetId");
              assertThat(entry.promptKey()).isEqualTo("prompt");
            });
  }

  @Test
  @DisplayName("a required string that does not say what it holds is not a button")
  void anUndeclaredInputIsNotFilled() {
    // document-authenticity passes every structural test — one step, one required string — and a
    // chat bar cannot fill it: there is nothing a user types that is a base64 document. Silence
    // costs a button rather than corrupting a run (ADR-068 §3).
    assertThat(
            serviceOver(
                    Map.of(
                        "document-authenticity",
                        blueprint("document-authenticity", ONE_UNDECLARED_STRING, 1)))
                .row("fr", ACTOR))
        .isEmpty();
  }

  @Test
  @DisplayName("a Studio the actor may not run is returned locked, never hidden")
  void aLockedStudioIsStillRendered() {
    StudioAccess locked = new StudioAccess(true, LockReason.REQUIRES_PLAN, null);

    List<ComposerStudio> row =
        serviceOver(Map.of("a-studio", blueprint("a-studio", ONE_TEXT_INPUT, 1)), locked)
            .row("fr", ACTOR);

    assertThat(row)
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.locked()).isTrue();
              assertThat(entry.lockedReason()).isEqualTo(LockReason.REQUIRES_PLAN);
            });
  }

  @Test
  @DisplayName("a Studio with no published blueprint is not a button")
  void aStudioWithoutABlueprintDoesNotBelong() {
    StudioCatalogService catalogService = mock(StudioCatalogService.class);
    StudioAccessService accessService = mock(StudioAccessService.class);
    BlueprintRepository blueprints = mock(BlueprintRepository.class);
    when(catalogService.browse(any(), anyString())).thenReturn(List.of(studio("a-studio")));
    when(accessService.evaluateAll(any(), anyString()))
        .thenReturn(Map.of("a-studio", StudioAccess.open()));
    when(blueprints.find(anyString(), anyString())).thenReturn(Optional.empty());

    assertThat(
            new ComposerStudioService(catalogService, accessService, blueprints, new ObjectMapper())
                .row("fr", ACTOR))
        .isEmpty();
  }
}
