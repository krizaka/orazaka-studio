package com.orazaka.studioservice.infrastructure.support;

import com.orazaka.studio.domain.model.PackBundle;
import com.orazaka.studioservice.domain.exception.PackInstallException;
import com.orazaka.studioservice.infrastructure.config.PackSourceProperties;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads a bundle directory on disk into the value the installer applies.
 *
 * <p>A bundle on disk is a manifest plus the files it names; a bundle in memory is one value. The
 * CLI has resolved the two apart since ADR-037 §3.2 and this is the same resolution on the service
 * side, which is what {@code #45} was missing: the platform could be <i>handed</i> a bundle over
 * HTTP and had no way to <i>find</i> one, so a shipped pack existed in the repository and in no
 * database until somebody typed a command.
 *
 * <p><b>Why this is not a second manifest parser.</b> It reads YAML into a map, resolves the two
 * kinds of reference a manifest holds — {@code studios[].blueprint} names a file, {@code i18n/} is
 * a directory of them — and hands the result to the same {@link ObjectMapper} binding that {@code
 * PackBundleController} already uses for the same record. The invariants stay where they are, in
 * {@link PackBundle}'s compact constructor; nothing about what a valid pack <i>is</i> lives here.
 *
 * <p><b>Shape is still the author's half.</b> {@code pack.schema.json} is checked by {@code orazaka
 * pack validate}, where a pack author can fix a manifest without a platform running. This path
 * refuses a malformed bundle through the record's constructor rather than by carrying a second copy
 * of the schema — and a bundle written against a newer grammar is refused by {@code apiVersion},
 * which is the check that has to hold.
 *
 * <p>The YAML loader is {@link SafeConstructor}: a pack is third-party content, and a manifest must
 * never be able to name a Java type for the platform to instantiate.
 */
@Component
public class PackBundleResolver {

  private static final Logger logger = LoggerFactory.getLogger(PackBundleResolver.class);

  /** What makes a directory a bundle. */
  private static final String MANIFEST = "pack.yaml";

  private static final String I18N_DIRECTORY = "i18n";

  /**
   * How far up the tree a relative source is looked for.
   *
   * <p>A service started by {@code spring-boot:run} works from its own module directory, four
   * levels below the repository root that holds {@code orazaka-packs}; eight leaves room for a
   * deeper layout without turning into a filesystem search.
   */
  private static final int MAX_WALK_UP = 8;

  private final ObjectMapper objectMapper;
  private final PackSourceProperties packSources;
  private final Path workingDirectory;

  /**
   * The constructor the container uses, said out loud.
   *
   * <p>This class has two, and the studio service <b>did not start at all</b> because of it: {@code
   * packBundleResolver} failed with "No default constructor found", {@code packBootstrap} failed
   * with it, and every {@code /api/v1/studios/**} request answered 502. No unit test saw it because
   * every test builds this class by hand, and no integration test boots this service's context —
   * the e2e gate is what found it, twice.
   *
   * <p><b>The first repair did not work, and why is the point.</b> Making the three-argument
   * variant {@code private} behind a static factory was supposed to leave "exactly one candidate".
   * It does not: {@code AutowiredAnnotationBeanPostProcessor} starts from {@code
   * getDeclaredConstructors()}, which includes private ones, and takes its single-candidate
   * shortcut only at {@code length == 1}. Two declared constructors, none annotated, no no-arg
   * fallback — the same {@code NoSuchMethodException}, from a class whose javadoc now claimed it
   * was fixed. The rule written to catch it agreed, because it had been given the same wrong
   * premise: it judged non-private constructors while the container counted all of them.
   *
   * <p>So the choice is <b>declared</b> rather than arranged for (AGENTS.md §12).
   * {@code @Autowired} names the injection point; the private constructor stays as the shared
   * implementation and the static factory stays as the test seam, and neither has to be mistaken
   * for anything.
   *
   * <p>That is [CFG-001]'s defect with a different injector: {@code SecurityProperties} and {@code
   * CoreProperties.OrchestrationConfig} were records the <i>binder</i> could not choose a
   * constructor for, and this was a component the <i>container</i> could not.
   *
   * @param objectMapper the application's mapper — the one the REST install path binds with
   * @param packSources where this deployment's packs come from
   */
  @Autowired
  public PackBundleResolver(ObjectMapper objectMapper, PackSourceProperties packSources) {
    this(objectMapper, packSources, Path.of(System.getProperty("user.dir")));
  }

  private PackBundleResolver(
      ObjectMapper objectMapper, PackSourceProperties packSources, Path workingDirectory) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper cannot be null");
    this.packSources = Objects.requireNonNull(packSources, "PackSourceProperties cannot be null");
    this.workingDirectory = Objects.requireNonNull(workingDirectory, "working directory required");
  }

  /**
   * A resolver that reads relative sources from a directory of the caller's choosing.
   *
   * <p>For tests, which must resolve against a temporary directory rather than the working
   * directory of whatever runner started them. A factory rather than a second constructor, so the
   * container has exactly one candidate.
   *
   * @param objectMapper the application's mapper — the one the REST install path binds with
   * @param packSources where this deployment's packs come from
   * @param workingDirectory the directory a relative source is resolved from
   * @return the resolver
   */
  static PackBundleResolver resolvingFrom(
      ObjectMapper objectMapper, PackSourceProperties packSources, Path workingDirectory) {
    return new PackBundleResolver(objectMapper, packSources, workingDirectory);
  }

  /**
   * Every bundle this deployment's sources offer.
   *
   * <p>A source that does not exist is skipped rather than fatal: sources are a deployment's
   * declaration of where it looks, and looking somewhere empty is not an error.
   *
   * @return the bundle directories, in source order and then by name
   */
  public List<Path> discover() {
    for (String registry : packSources.registries()) {
      logger.warn(
          "Pack source {} needs the cloud control plane; this deployment is local, so nothing is"
              + " read from it (AGENTS.md §0)",
          registry);
    }
    List<Path> bundles = new ArrayList<>();
    for (String source : packSources.directories()) {
      Optional<Path> directory = locate(source);
      if (directory.isEmpty()) {
        logger.info("Pack source {} is not present here — nothing to bootstrap from it", source);
        continue;
      }
      bundles.addAll(bundlesIn(directory.get()));
    }
    return bundles;
  }

  /**
   * Reads one bundle directory.
   *
   * @param bundleDirectory the directory holding {@code pack.yaml}
   * @return the resolved manifest, with every file it names already read
   * @throws PackInstallException when the directory is not a readable bundle
   */
  public PackBundle read(Path bundleDirectory) {
    Objects.requireNonNull(bundleDirectory, "bundle directory must not be null");
    Map<String, Object> manifest = loadManifest(bundleDirectory.resolve(MANIFEST));
    // Before anything else this bundle claims is believed: a ruleset is a suite of assertions
    // pointed at the user's document, and one that cannot fail says nothing while reporting a
    // verdict (ADR-072). Refused here because a bundle that does not resolve does not install,
    // and because a ruleset author outside this repository has no suite of ours to run.
    RulesetFixtureValidator.verify(bundleDirectory, objectMapper);

    Map<String, Object> resolved = new LinkedHashMap<>(manifest);
    // `requires` is the manifest's word for what the platform must already have; `capabilities` is
    // the record's word for the half of it the installer registers. The other half —
    // `orazakaVersion` — is recorded in the manifest and enforced by nothing yet, so it is dropped
    // here rather than silently bound to a component that does not exist.
    Object requires = resolved.remove("requires");
    resolved.put("capabilities", contributedCapabilities(requires));
    resolved.put("studios", studios(bundleDirectory, manifest.get("studios")));
    resolved.put("translations", translations(bundleDirectory));

    try {
      return objectMapper.convertValue(resolved, PackBundle.class);
    } catch (RuntimeException invalid) {
      // The record's constructor is the authority on what a bundle may be, and its message says
      // which invariant was broken. Wrapped only to name the directory it came from.
      throw new PackInstallException(
          "Bundle at " + bundleDirectory + " is not installable: " + invalid.getMessage(), invalid);
    }
  }

  /** The bundles directly inside one source directory, ordered so a run is reproducible. */
  private static List<Path> bundlesIn(Path source) {
    try (Stream<Path> entries = Files.list(source)) {
      return entries
          .filter(Files::isDirectory)
          .filter(candidate -> Files.isReadable(candidate.resolve(MANIFEST)))
          .sorted()
          .toList();
    } catch (IOException unreadable) {
      throw new PackInstallException("Pack source " + source + " could not be listed", unreadable);
    }
  }

  /**
   * Resolves a configured source to a directory.
   *
   * <p>Absolute paths are used exactly as given. A relative one is looked for from the working
   * directory upwards, because the working directory of a service is its own module and the packs
   * sit at the root of the tree that contains it.
   */
  private Optional<Path> locate(String source) {
    Path declared = Path.of(source);
    if (declared.isAbsolute()) {
      return Files.isDirectory(declared) ? Optional.of(declared) : Optional.empty();
    }
    Path from = workingDirectory.toAbsolutePath();
    for (int depth = 0; depth < MAX_WALK_UP && from != null; depth++) {
      Path candidate = from.resolve(declared);
      if (Files.isDirectory(candidate)) {
        return Optional.of(candidate);
      }
      from = from.getParent();
    }
    return Optional.empty();
  }

  /** Loads {@code pack.yaml}, refusing anything that is not a mapping. */
  private static Map<String, Object> loadManifest(Path manifest) {
    if (!Files.isReadable(manifest)) {
      throw new PackInstallException(
          "No readable " + MANIFEST + " at " + manifest + " — that file is what makes a pack");
    }
    try (Reader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
      Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
      if (!(loaded instanceof Map<?, ?> mapping)) {
        throw new PackInstallException(manifest + " is not a manifest mapping");
      }
      Map<String, Object> manifestMap = new LinkedHashMap<>();
      mapping.forEach((key, value) -> manifestMap.put(String.valueOf(key), value));
      return manifestMap;
    } catch (IOException unreadable) {
      throw new PackInstallException(manifest + " could not be read", unreadable);
    }
  }

  /**
   * The capabilities a bundle contributes, or none when it contributes nothing.
   *
   * <p>The two contract halves are objects in the manifest, {@code jsonb} in the table and text in
   * {@link com.orazaka.studio.domain.model.PackCapability} — the same resolution a blueprint's
   * three schemas get, for the same reason (ADR-069). Re-serialised here rather than bound as a
   * map, so the installer and the REST path hand the registry the identical string.
   */
  private Object contributedCapabilities(Object requires) {
    if (!(requires instanceof Map<?, ?> declared) || declared.get("capabilities") == null) {
      return List.of();
    }
    if (!(declared.get("capabilities") instanceof List<?> capabilities)) {
      throw new PackInstallException("requires.capabilities is not a list");
    }
    List<Object> resolved = new ArrayList<>();
    for (Object entry : capabilities) {
      if (!(entry instanceof Map<?, ?> capability)) {
        throw new PackInstallException("requires.capabilities holds a malformed entry");
      }
      Map<String, Object> copy = new LinkedHashMap<>();
      capability.forEach((key, value) -> copy.put(String.valueOf(key), value));
      copy.put("inputSchema", asJsonText(copy.get("inputSchema")));
      copy.put("outputSchema", asJsonText(copy.get("outputSchema")));
      resolved.add(copy);
    }
    return resolved;
  }

  /** A declared schema as the text the row stores; {@code {}} when the manifest declared none. */
  private String asJsonText(Object declared) {
    return declared == null ? "{}" : objectMapper.writeValueAsString(declared);
  }

  /** Each Studio, with the blueprint file the manifest names read in. */
  private List<Object> studios(Path bundleDirectory, Object declared) {
    if (!(declared instanceof List<?> entries)) {
      throw new PackInstallException(bundleDirectory + "/" + MANIFEST + " declares no studios");
    }
    List<Object> studios = new ArrayList<>();
    for (Object entry : entries) {
      if (!(entry instanceof Map<?, ?> studio)) {
        throw new PackInstallException(
            bundleDirectory + "/" + MANIFEST + " has a malformed studio");
      }
      Map<String, Object> resolved = new LinkedHashMap<>();
      studio.forEach((key, value) -> resolved.put(String.valueOf(key), value));
      resolved.put(
          "blueprint", blueprint(bundleDirectory, String.valueOf(resolved.get("blueprint"))));
      studios.add(resolved);
    }
    return studios;
  }

  /**
   * Reads one blueprint file.
   *
   * <p>The three schema fields are re-serialised rather than passed through: the columns behind
   * them are {@code jsonb} and the record carries them as text, exactly as the CLI's loader does.
   * Reading them as trees and writing them back keeps this path from inventing a second JSON
   * dialect.
   */
  private Object blueprint(Path bundleDirectory, String relativePath) {
    Path file = bundleDirectory.resolve(relativePath);
    if (!Files.isReadable(file)) {
      throw new PackInstallException("Blueprint not found: " + relativePath);
    }
    JsonNode raw = readJson(file);
    for (String required : List.of("version", "definition", "inputSchema")) {
      if (raw.get(required) == null) {
        throw new PackInstallException(relativePath + " declares no " + required);
      }
    }
    Map<String, Object> blueprint = new LinkedHashMap<>();
    blueprint.put("version", raw.get("version").asString());
    blueprint.put(
        "status", raw.path("status").isMissingNode() ? "DRAFT" : raw.get("status").asString());
    blueprint.put("definition", raw.get("definition").toString());
    blueprint.put("inputSchema", raw.get("inputSchema").toString());
    blueprint.put(
        "configSchema",
        raw.get("configSchema") == null ? "{}" : raw.get("configSchema").toString());
    blueprint.put("estimatedCredits", raw.path("estimatedCredits").asLong(0L));
    blueprint.put(
        "changelog",
        raw.path("changelog").isMissingNode() ? null : raw.get("changelog").asString());
    blueprint.put(
        "createdBy",
        raw.path("createdBy").isMissingNode() ? "system" : raw.get("createdBy").asString());
    return blueprint;
  }

  /** Reads a blueprint as a tree, naming the file when it is not JSON. */
  private JsonNode readJson(Path file) {
    try {
      return objectMapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException | RuntimeException malformed) {
      throw new PackInstallException(file + " is not readable JSON", malformed);
    }
  }

  /** Every {@code i18n/<locale>.yaml}, keyed by locale. */
  private static Map<String, Object> translations(Path bundleDirectory) {
    Path directory = bundleDirectory.resolve(I18N_DIRECTORY);
    if (!Files.isDirectory(directory)) {
      return Map.of();
    }
    Map<String, Object> translations = new LinkedHashMap<>();
    try (Stream<Path> files = Files.list(directory)) {
      files
          .filter(Files::isRegularFile)
          .filter(file -> isYaml(file.getFileName().toString()))
          .sorted()
          .forEach(
              file -> {
                String name = file.getFileName().toString();
                translations.put(name.substring(0, name.lastIndexOf('.')), loadManifest(file));
              });
    } catch (IOException unreadable) {
      throw new PackInstallException(directory + " could not be listed", unreadable);
    }
    return translations;
  }

  private static boolean isYaml(String fileName) {
    String lower = fileName.toLowerCase(Locale.ROOT);
    return lower.endsWith(".yaml") || lower.endsWith(".yml");
  }
}
