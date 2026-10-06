package com.orazaka.studioservice.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.orazaka.test.architecture.SqlBoundaryRules;
import com.orazaka.test.architecture.Workspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The seed-level fitness functions of ADR-034 §15.
 *
 * <p>They live here rather than in {@code BlueprintPublishService} on purpose. Checking a {@code
 * featureKey} against the capability registry at publish time would mean reading another context's
 * database, which is exactly the coupling SEAM-001/002 forbid — so the check runs at build time
 * over the seed files, where both contexts' data is plain text and no runtime dependency is
 * created.
 *
 * <p>What they catch is a real and quiet failure: a published blueprint naming a capability that is
 * missing or disabled fails at <b>run</b> time, after the actor's credits are already held.
 *
 * <p><b>They read {@code orazaka-packs/}, not {@code 80-studio.sql} (phase D).</b> The blueprints
 * moved out of the seed and into bundles (ADR-039); these checks followed them, because a fitness
 * function that keeps reading the old location is one that passes for the wrong reason. The class
 * caught its own obsolescence on the migration commit — {@code assertTrue(parsedAny, "no blueprint
 * definitions parsed — the fitness function is blind")} failed the build the moment the seed
 * emptied, which is the whole argument for writing that assertion in the first place.
 *
 * <p>The capability rows are still read from {@code 30-jobs-config.sql}: capabilities are the
 * engine's, not a pack's, and the ones a DATA pack names must already exist there.
 */
class BlueprintFitnessTest {

  /**
   * Matches one seeded capability row and captures its key and enabled flag.
   *
   * <p>Line-anchored and greedy on purpose. A DOTALL pattern requiring a trailing delimiter
   * silently skips the last row of an INSERT — which has none — and a fitness function that quietly
   * stops seeing the newest capability is worse than no fitness function at all.
   */
  private static final Pattern CAPABILITY_ROW =
      Pattern.compile(
          // Reluctant and DOTALL since ADR-069: a capability tuple spans several lines now,
          // because its contract is two JSON Schemas rather than a template string. A
          // line-anchored `.*` stopped matching and this rule said so instead of passing.
          "^\\('(orazaka\\.[a-z0-9.]+)',.*?,\\s*(true|false)\\)",
          Pattern.MULTILINE | Pattern.DOTALL);

  private static final Pattern FEATURE_KEY_IN_BLUEPRINT =
      Pattern.compile("\"featureKey\"\\s*:\\s*\"([^\"]+)\"");

  private static final Pattern ENTITLEMENT_KEY_IN_STUDIO =
      Pattern.compile("'(studio\\.[a-z0-9.-]+)'");

  /** A {@code blueprint:} path in a manifest. */
  private static final Pattern BLUEPRINT_PATH =
      Pattern.compile("^\\s*blueprint:\\s*(\\S+)\\s*$", Pattern.MULTILINE);

  /** A studio's {@code status:} in a manifest, to pair a published Studio with its blueprint. */
  private static final Pattern STUDIO_STATUS =
      Pattern.compile("^\\s*status:\\s*([A-Z]+)\\s*$", Pattern.MULTILINE);

  /** A field access on an upstream step's result: {@code {{steps.<out>.<field>}}}. */
  private static final Pattern STEP_FIELD_ACCESS =
      Pattern.compile("\\{\\{steps\\.([A-Za-z0-9_-]+)\\.([A-Za-z0-9_]+)}}");

  /**
   * The capability contract, <b>read from where it is declared</b> (ADR-069).
   *
   * <p>This was two hand-written maps — {@code PUBLISHED_FIELDS} and {@code CONSUMED_INPUTS} —
   * transcribed from the executors into this file, with the javadoc "duplicated from the executors
   * on purpose: that is what a fitness function is". It was not: a fitness function compares two
   * things the system already says, and there was only ever one. So the copy rotted — it said
   * {@code orazaka.core.media.audio} publishes {@code url}, transcribed while that row still called
   * itself "Audio Generation", and a blueprint reading the field the executor really publishes
   * ({@code analysis}) would have FAILED this rule while one reading a field nothing produces
   * passed.
   *
   * <p>Both halves are declared now — {@code input_schema} and {@code output_schema} on the
   * capability row, seeded for the platform's own capabilities and written by the installer from a
   * pack's manifest — so this reads data on both sides and a step is checkable in both directions.
   */
  private record Contract(Set<String> inputs, Set<String> outputs) {}

  /** A seeded capability row with its two contract literals, in the seed's dollar-quoted form. */
  private static final Pattern SEEDED_CONTRACT =
      Pattern.compile(
          "\\('(?<key>orazaka\\.[a-z0-9.]+)',[^\\n]*\\n\\s*\\$\\$(?<in>\\{.*?})\\$\\$::jsonb,"
              + "\\n\\s*\\$\\$(?<out>\\{.*?})\\$\\$::jsonb",
          Pattern.DOTALL);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static Path initdb;
  private static Path packs;

  @BeforeAll
  static void locateRoots() {
    // The studio's own bootstrap is in this repository; the packs and the job plane's capability
    // seed are in theirs, read from the workspace (skipped in a standalone clone).
    Path start = Path.of(System.getProperty("user.dir"));
    initdb = SqlBoundaryRules.locateInitDb(start);
    packs = Workspace.packs(start);
  }

  /**
   * Every blueprint a bundle ships, parsed.
   *
   * <p>Reads the file the manifest names rather than trusting a directory convention: a blueprint
   * this returns is one an install would actually write, and one it does not return is one no
   * install can reach.
   */
  private static List<JsonNode> bundledBlueprints() throws IOException {
    List<JsonNode> definitions = new ArrayList<>();
    try (var manifests = Files.walk(packs, 3)) {
      for (Path manifest : manifests.filter(p -> p.endsWith("pack.yaml")).toList()) {
        Path bundle = manifest.getParent();
        for (String relative : blueprintPathsOf(Files.readString(manifest))) {
          Path file = bundle.resolve(relative);
          assertTrue(
              Files.isRegularFile(file),
              () -> manifest + " names a missing blueprint: " + relative);
          definitions.add(MAPPER.readTree(Files.readString(file)));
        }
      }
    }
    return definitions;
  }

  /** The {@code blueprint:} paths a manifest declares. Line-based: the manifests are flat YAML. */
  private static List<String> blueprintPathsOf(String manifest) {
    List<String> paths = new ArrayList<>();
    Matcher matcher = BLUEPRINT_PATH.matcher(manifest);
    while (matcher.find()) {
      paths.add(matcher.group(1).trim());
    }
    return paths;
  }

  /** Capability keys the bundles themselves declare under {@code requires.capabilities}. */
  private static Set<String> contributedCapabilities() throws IOException {
    Set<String> contributed = new LinkedHashSet<>();
    try (Stream<Path> manifests = Files.walk(packs, 2)) {
      for (Path manifest :
          manifests.filter(p -> p.getFileName().toString().equals("pack.yaml")).toList()) {
        Matcher declared = CONTRIBUTED_CAPABILITY.matcher(Files.readString(manifest));
        while (declared.find()) {
          contributed.add(declared.group(1));
        }
      }
    }
    return contributed;
  }

  /** {@code - key: orazaka.echo.text.reverse} inside a manifest's requires.capabilities. */
  private static final Pattern CONTRIBUTED_CAPABILITY =
      Pattern.compile("(?m)^\\s*-\\s*key:\\s*(orazaka\\.[a-z0-9.]+)\\s*$");

  @Test
  @DisplayName("[ADR-034 §15.2] every featureKey of a bundled blueprint exists and is enabled")
  void bundledBlueprintsOnlyReferenceEnabledCapabilities() throws IOException {
    Set<String> enabled = new LinkedHashSet<>();
    Set<String> known = new LinkedHashSet<>();
    Matcher capabilities = CAPABILITY_ROW.matcher(read("30-jobs-config.sql"));
    while (capabilities.find()) {
      known.add(capabilities.group(1));
      if ("true".equals(capabilities.group(2))) {
        enabled.add(capabilities.group(1));
      }
    }
    assertTrue(!known.isEmpty(), "no capability rows parsed — the fitness function is blind");

    // A pack may CONTRIBUTE a capability, and then it is not in the core seed by construction —
    // that is what a Tier-C or Tier-W pack IS. Reading only 30-jobs-config.sql made this rule
    // fail on the first pack that brought its own capability (echo-toolkit, ADR-049), which is
    // the rule being narrower than the platform rather than the pack being wrong. A capability a
    // bundle declares in `requires.capabilities` is resolvable for that bundle's blueprints; the
    // installer verifies the same claim at install time against the live registry.
    for (String contributed : contributedCapabilities()) {
      known.add(contributed);
      enabled.add(contributed);
    }

    List<String> violations = new ArrayList<>();
    boolean referencedAny = false;
    for (JsonNode blueprint : bundledBlueprints()) {
      Matcher referenced = FEATURE_KEY_IN_BLUEPRINT.matcher(blueprint.toString());
      while (referenced.find()) {
        referencedAny = true;
        String featureKey = referenced.group(1);
        if (!known.contains(featureKey)) {
          violations.add(featureKey + " is named by a bundled blueprint but does not exist");
        } else if (!enabled.contains(featureKey)) {
          violations.add(featureKey + " is named by a bundled blueprint but is disabled");
        }
      }
    }
    assertTrue(referencedAny, "no blueprint featureKeys parsed — the fitness function is blind");

    assertTrue(
        violations.isEmpty(),
        () ->
            "A published blueprint naming a missing or disabled capability fails at RUN time, after"
                + " the actor's credits are held (ADR-034 §15):\n  "
                + String.join("\n  ", violations));
  }

  /**
   * [ADR-034 §15.3] A PUBLISHED Studio must ship a PUBLISHED blueprint.
   *
   * <p>This slot used to check that every Studio's entitlement key appeared in {@code
   * 70-billing.sql}. That check is gone because what it guarded is gone: grants are no longer
   * seeded, they are DERIVED from each Studio's own {@code entitlementKey} by {@code
   * PackInstallerService.toProvision}, and {@code PackStudio}'s constructor refuses any key that is
   * not {@code studio.<key>} — the two halves can no longer disagree, so there is nothing left to
   * compare ({@code PackBundleTest} pins it).
   *
   * <p>What replaces it is the failure that IS still possible in a bundle: a Studio published on
   * the shelf whose blueprint says DRAFT. The catalogue would show it, the run path would find no
   * published version, and the actor meets the error after clicking Run.
   */
  @Test
  @DisplayName("[ADR-034 §15.3] a PUBLISHED Studio ships a PUBLISHED blueprint")
  void publishedStudiosShipPublishedBlueprints() throws IOException {
    List<String> violations = new ArrayList<>();
    boolean checkedAny = false;

    try (var manifests = Files.walk(packs, 3)) {
      for (Path manifest : manifests.filter(path -> path.endsWith("pack.yaml")).toList()) {
        Path bundle = manifest.getParent();
        String text = Files.readString(manifest);
        List<String> paths = blueprintPathsOf(text);
        List<String> statuses = new ArrayList<>();
        Matcher status = STUDIO_STATUS.matcher(text);
        while (status.find()) {
          statuses.add(status.group(1));
        }
        for (int index = 0; index < paths.size(); index++) {
          checkedAny = true;
          JsonNode blueprint = MAPPER.readTree(Files.readString(bundle.resolve(paths.get(index))));
          String blueprintStatus = blueprint.path("status").asString("DRAFT");
          // statuses holds the catalog's status first when the bundle is catalogued, so the
          // Studio's own is read from the tail — the same order the manifest declares them in.
          String studioStatus =
              statuses.size() > index
                  ? statuses.get(statuses.size() - paths.size() + index)
                  : "DRAFT";
          if ("PUBLISHED".equals(studioStatus) && !"PUBLISHED".equals(blueprintStatus)) {
            violations.add(
                bundle.getFileName()
                    + "/"
                    + paths.get(index)
                    + " is "
                    + blueprintStatus
                    + " but its Studio is PUBLISHED");
          }
        }
      }
    }

    assertTrue(checkedAny, "no bundled blueprints checked — the fitness function is blind");
    assertTrue(
        violations.isEmpty(),
        () ->
            "A Studio on the shelf whose blueprint is not published has no version to run: the"
                + " actor meets the error after clicking Run:\n  "
                + String.join("\n  ", violations));
  }

  @Test
  @DisplayName("[ADR-069] every {{steps.x.field}} names a field its capability DECLARES as output")
  void bundledBlueprintsOnlyReadFieldsTheExecutorsProduce() throws IOException {
    Map<String, Contract> contracts = declaredContracts();
    assertTrue(contracts.size() >= 8, "no declared contract read — the fitness function is blind");
    List<String> violations = new ArrayList<>();
    boolean parsedAny = false;

    for (JsonNode bundled : bundledBlueprints()) {
      JsonNode blueprint = bundled.path("definition");
      if (!blueprint.has("steps")) {
        continue;
      }
      parsedAny = true;
      violations.addAll(unreadableFields(blueprint, contracts));
    }

    assertTrue(parsedAny, "no blueprint definitions parsed — the fitness function is blind");
    assertTrue(
        violations.isEmpty(),
        () ->
            "An unresolved reference renders as the empty string, so this does not fail the run —"
                + " the actor is simply billed for an artefact that arrives blank:\n  "
                + String.join("\n  ", violations));
  }

  @Test
  @DisplayName("[ADR-043] a shipped step that SKIPs its failures must say in writing why")
  void skippingStepsCarryAWrittenRationale() throws IOException {
    List<String> unjustified = new ArrayList<>();
    boolean checkedAny = false;

    for (JsonNode bundled : bundledBlueprints()) {
      JsonNode definition = bundled.path("definition");
      if (!definition.has("steps")) {
        continue;
      }
      String studio = definition.path("studioKey").asString("?");
      for (JsonNode step : definition.get("steps")) {
        checkedAny = true;
        if (!"SKIP".equals(step.path("onError").asString(""))) {
          continue;
        }
        String rationale = step.path("skipRationale").asString("");
        if (rationale.isBlank() || rationale.length() < MINIMUM_RATIONALE) {
          unjustified.add(studio + "/" + step.path("id").asString("?"));
        }
      }
    }

    assertTrue(checkedAny, "no bundled steps checked — the fitness function is blind");
    assertTrue(
        unjustified.isEmpty(),
        () ->
            "onError: SKIP means \"a failure here is acceptable\", and that is a product claim which"
                + " has to be argued rather than defaulted to. Every one of these swallowed its"
                + " step's failure with nothing said about why — which is exactly how"
                + " trade-showcase ran green for three phases while its vision fan-out had never"
                + " once succeeded (ADR-042). Add a `skipRationale` saying what the run is still"
                + " worth without this step, or change the policy to FAIL:\n  "
                + String.join("\n  ", unjustified));
  }

  /**
   * Long enough to be a sentence, short enough not to be a hurdle.
   *
   * <p>The point is not the character count — it is that a `skipRationale` cannot be satisfied with
   * "ok" or "n/a", which is what a presence-only check invites.
   */

  /**
   * The rule that carried {@code payload_template}'s defaults into a schema is gone with the column
   * (ADR-069 §5).
   *
   * <p>It existed for one release, to make sure a default that only ever lived in that column —
   * {@code ${size:1024x1024}} — was written into a blueprint's input schema before the column was
   * dropped. The capability's own {@code input_schema} carries defaults now, and the two checks
   * above read it, so the migration it guarded is complete and the guard has nothing left to
   * compare. Deleted rather than left passing over an empty population, which is what GOV-006
   * exists to catch.
   */
  @Test
  @DisplayName("[ADR-069] no step passes an input its capability does not DECLARE")
  void bundledBlueprintsOnlyPassInputsTheExecutorsRead() throws IOException {
    // The other direction of the same contract. An input the capability does not declare is either
    // work bought and discarded (audit #35: a b-roll clip rendered, billed and never composited)
    // or a promise the blueprint's author believed. Read from input_schema now, not from a
    // transcription of what the executors happen to read — and the executor's own half of it is a
    // contract test the worker runs against its own code (ADR-069 §6).
    Map<String, Contract> contracts = declaredContracts();
    List<String> ignored = new ArrayList<>();
    int inspected = 0;
    for (JsonNode blueprint : bundledBlueprints()) {
      String studioKey = blueprint.path("definition").path("studioKey").asString("?");
      for (JsonNode step : blueprint.path("definition").path("steps")) {
        Contract contract = contracts.get(step.path("featureKey").asString(""));
        if (contract == null || contract.inputs().isEmpty()) {
          continue;
        }
        Set<String> read = contract.inputs();
        inspected++;
        for (String input : step.path("inputs").propertyNames()) {
          if (!read.contains(input)) {
            ignored.add(
                studioKey
                    + "."
                    + step.path("id").asString("?")
                    + " passes '"
                    + input
                    + "' to "
                    + step.path("featureKey").asString("")
                    + ", which declares "
                    + read);
          }
        }
      }
    }
    assertTrue(inspected > 0, "no step inspected — the fitness function is blind");
    assertTrue(
        ignored.isEmpty(),
        "an input the capability does not declare is work bought and discarded, or a promise"
            + " nobody keeps (audit #35, #43):\n  "
            + String.join("\n  ", ignored));
  }

  private static final int MINIMUM_RATIONALE = 40;

  /** The field accesses in one blueprint that its own steps cannot satisfy. */
  private static List<String> unreadableFields(
      JsonNode blueprint, Map<String, Contract> contracts) {
    Map<String, Set<String>> publishedByOut = new LinkedHashMap<>();
    for (JsonNode step : blueprint.get("steps")) {
      String out = step.path("out").asString(null);
      if (out == null) {
        continue;
      }
      if ("TRANSFORM".equals(step.path("kind").asString(""))) {
        // A transform publishes exactly the input keys it declares.
        publishedByOut.put(out, new LinkedHashSet<>(step.path("inputs").propertyNames()));
      } else if (step.path("forEach").isMissingNode()) {
        // A fan-out publishes a list, not an object; only single steps expose fields.
        Contract contract = contracts.get(step.path("featureKey").asString(""));
        if (contract != null && !contract.outputs().isEmpty()) {
          publishedByOut.put(out, contract.outputs());
        }
      }
    }

    List<String> violations = new ArrayList<>();
    Matcher access = STEP_FIELD_ACCESS.matcher(blueprint.toString());
    while (access.find()) {
      Set<String> published = publishedByOut.get(access.group(1));
      if (published != null && !published.contains(access.group(2))) {
        violations.add(
            blueprint.path("studioKey").asString("?")
                + ": {{steps."
                + access.group(1)
                + "."
                + access.group(2)
                + "}} — that step publishes "
                + published);
      }
    }
    return violations;
  }

  /** The entitlement keys the studio seed declares, taken from the studio INSERT only. */
  private static Set<String> entitlementKeysOf(String studioSql) {
    Set<String> keys = new LinkedHashSet<>();
    Matcher matcher = ENTITLEMENT_KEY_IN_STUDIO.matcher(studioSql);
    while (matcher.find()) {
      String key = matcher.group(1);
      // Only the concrete per-Studio keys: a wildcard tier is granted by definition.
      if (!key.startsWith("studio.tier.")) {
        keys.add(key);
      }
    }
    return keys;
  }

  private static String read(String fileName) throws IOException {
    return Files.readString(Workspace.initDbFile(initdb, fileName));
  }

  /**
   * Every capability contract this repository declares: the seeded rows, plus what each pack
   * manifest contributes.
   *
   * <p>Two sources because there are two declarers, and a rule that read only the seed would judge
   * the platform's capabilities and skip every pack's — which is exactly the population a contract
   * exists to constrain.
   */
  private static Map<String, Contract> declaredContracts() throws IOException {
    Map<String, Contract> contracts = new LinkedHashMap<>();
    Matcher seeded = SEEDED_CONTRACT.matcher(read("30-jobs-config.sql"));
    while (seeded.find()) {
      contracts.put(
          seeded.group("key"),
          new Contract(
              propertyNamesOf(MAPPER.readTree(seeded.group("in"))),
              propertyNamesOf(MAPPER.readTree(seeded.group("out")))));
    }
    for (Path manifest : manifests()) {
      Object loaded =
          new org.yaml.snakeyaml.Yaml(
                  new org.yaml.snakeyaml.constructor.SafeConstructor(
                      new org.yaml.snakeyaml.LoaderOptions()))
              .load(Files.readString(manifest));
      if (!(loaded instanceof Map<?, ?> pack) || !(pack.get("requires") instanceof Map<?, ?> req)) {
        continue;
      }
      if (!(req.get("capabilities") instanceof List<?> capabilities)) {
        continue;
      }
      for (Object entry : capabilities) {
        if (!(entry instanceof Map<?, ?> capability)) {
          continue;
        }
        contracts.put(
            String.valueOf(capability.get("key")),
            new Contract(
                declaredPropertyNames(capability.get("inputSchema")),
                declaredPropertyNames(capability.get("outputSchema"))));
      }
    }
    return contracts;
  }

  /** The property names of a JSON Schema, or an empty set when it declares none. */
  private static Set<String> propertyNamesOf(JsonNode schema) {
    return new LinkedHashSet<>(schema.path("properties").propertyNames());
  }

  /** The same, for a schema that arrived as a YAML mapping. */
  private static Set<String> declaredPropertyNames(Object schema) {
    if (schema instanceof Map<?, ?> mapping && mapping.get("properties") instanceof Map<?, ?> p) {
      Set<String> names = new LinkedHashSet<>();
      p.keySet().forEach(key -> names.add(String.valueOf(key)));
      return names;
    }
    return Set.of();
  }

  /** Every shipped pack manifest. */
  private static List<Path> manifests() throws IOException {
    try (java.util.stream.Stream<Path> entries = Files.list(packs)) {
      return entries
          .map(dir -> dir.resolve("pack.yaml"))
          .filter(Files::isRegularFile)
          .sorted()
          .toList();
    }
  }
}
