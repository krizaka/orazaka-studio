package com.krizaka.orazaka.studioservice.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.krizaka.orazaka.studio.domain.model.Blueprint;
import com.krizaka.orazaka.studio.domain.model.BlueprintStatus;
import com.krizaka.orazaka.studio.domain.model.StepKind;
import com.krizaka.orazaka.studioservice.domain.port.BlueprintRepository;
import com.krizaka.orazaka.test.architecture.Workspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Parses the blueprint printed in {@code docs/STUDIO_ARCHITECTURE.md} §6 with the real validator.
 *
 * <p>A design document is the one artefact nothing else checks. That example is what every future
 * blueprint gets copied from, so an id that violates the step-id grammar or a placeholder outside
 * the templating grammar propagates into real Studios — and is discovered by an admin, at publish
 * time, months later.
 *
 * <p>This is a documentation fitness function: the doc and the parser cannot drift apart without
 * failing the build.
 */
class DesignExampleBlueprintTest {

  /** The first fenced {@code jsonc} block of the design — §6, the DSL example. */
  private static final Pattern JSONC_BLOCK = Pattern.compile("```jsonc\\n(.*?)```", Pattern.DOTALL);

  /** {@code jsonc} allows comments; the parser does not. */
  private static final Pattern LINE_COMMENT = Pattern.compile("(?m)\\s//.*$");

  @Test
  @DisplayName(
      "[docs §6] the DSL example in the design parses and validates against the real rules")
  void designExampleIsAValidBlueprint() throws IOException {
    String example = extractExample();

    Blueprint parsed = parse(example);

    assertNotNull(parsed);
    assertTrue(parsed.steps().size() >= 5, "the example demonstrates a multi-step DAG");
    // The claims the example is meant to demonstrate, asserted rather than assumed.
    assertTrue(
        parsed.steps().stream().anyMatch(step -> step.forEach() != null),
        "the example must demonstrate a fan-out");
    assertTrue(
        parsed.steps().stream().anyMatch(step -> step.kind() == StepKind.APPROVAL),
        "the example must demonstrate an approval gate");
    assertTrue(
        parsed.steps().stream().anyMatch(step -> step.kind() == StepKind.CONNECTOR),
        "the example must demonstrate a connector step");
    assertTrue(
        parsed.steps().stream().anyMatch(step -> step.condition() != null),
        "the example must demonstrate a conditional step");
    assertEquals(3, parsed.outputs().size(), "the example declares three outputs");
  }

  /**
   * Parses through the same adapter the run path uses.
   *
   * <p>Deliberately not a hand-rolled reader: a doc test that validated the example more leniently
   * than production would pass while the example stayed broken.
   */
  private static Blueprint parse(String definitionJson) {
    ObjectMapper mapper = new ObjectMapper();
    var definition = mapper.readTree(definitionJson);
    try {
      return com.krizaka.orazaka.studioservice.infrastructure.adapter.persistence
          .BlueprintMapperBridge.toBlueprint(
          definition.path("studioKey").asString(),
          definition.path("version").asString(),
          BlueprintStatus.PUBLISHED,
          definition,
          "{\"type\":\"object\"}",
          Map.of(),
          0,
          null,
          null);
    } catch (RuntimeException invalid) {
      return fail(
          "docs/STUDIO_ARCHITECTURE.md §6 no longer describes a valid blueprint: "
              + invalid.getMessage());
    }
  }

  private static String extractExample() throws IOException {
    Path design =
        Workspace.root(Path.of(System.getProperty("user.dir")), "docs/STUDIO_ARCHITECTURE.md")
            .resolve("docs")
            .resolve("STUDIO_ARCHITECTURE.md");
    assertTrue(Files.isRegularFile(design), "design document not found at " + design);

    Matcher matcher = JSONC_BLOCK.matcher(Files.readString(design));
    assertTrue(matcher.find(), "design §6 must contain a ```jsonc blueprint example");
    return LINE_COMMENT.matcher(matcher.group(1)).replaceAll("");
  }

  /** Guards against the example silently losing the constructs it exists to teach. */
  @Test
  @DisplayName("[docs §6] every step id in the example obeys the kebab-case grammar")
  void exampleStepIdsObeyTheGrammar() throws IOException {
    Blueprint parsed = parse(extractExample());

    List<String> ids = parsed.steps().stream().map(step -> step.id()).toList();
    assertTrue(
        ids.stream().allMatch(id -> id.matches("^[a-z][a-z0-9-]{0,59}$")),
        () -> "step ids must be kebab-case, was: " + ids);
  }

  /** Compile-time proof the port is what production depends on. */
  @SuppressWarnings("unused")
  private static final Class<BlueprintRepository> PORT = BlueprintRepository.class;
}
