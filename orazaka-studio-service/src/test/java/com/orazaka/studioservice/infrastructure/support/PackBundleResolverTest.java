package com.orazaka.studioservice.infrastructure.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orazaka.studio.domain.model.PackBundle;
import com.orazaka.studio.domain.model.PackKind;
import com.orazaka.studioservice.domain.exception.PackInstallException;
import com.orazaka.studioservice.infrastructure.config.PackSourceProperties;
import com.orazaka.test.architecture.Workspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * The manifests this repository ships are readable by the platform that ships them (ADR-068).
 *
 * <p>Read from {@code orazaka-packs/} rather than from a fixture, on purpose: a resolver proved
 * against a manifest written for it proves the resolver and says nothing about what ships. What
 * bootstrap depends on is that <b>these</b> bundles resolve, and a manifest that stops resolving
 * must fail here rather than in a container at start-up.
 */
class PackBundleResolverTest {

  /** The workspace, where the shipped bundles are; tests that read them skip without it. */
  private static final Path REPOSITORY_ROOT =
      Workspace.rootOrRepository(Path.of(System.getProperty("user.dir")));

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static PackBundleResolver resolverOn(Path workingDirectory) {
    return PackBundleResolver.resolvingFrom(
        MAPPER, new PackSourceProperties(List.of("orazaka-packs")), workingDirectory);
  }

  @Test
  @DisplayName("every bundle this repository ships resolves into an installable value")
  void everyShippedBundleResolves() {
    Workspace.require(REPOSITORY_ROOT, "PACK-BUNDLE");
    PackBundleResolver resolver = resolverOn(REPOSITORY_ROOT);

    List<Path> bundles = resolver.discover();

    assertThat(bundles).as("the repository ships bundles").isNotEmpty();
    for (Path directory : bundles) {
      PackBundle bundle = resolver.read(directory);
      assertThat(bundle.key()).as("%s names itself", directory).isNotBlank();
      assertThat(bundle.studios()).as("%s ships Studios", bundle.key()).isNotEmpty();
      bundle
          .studios()
          .forEach(
              studio ->
                  assertThat(studio.blueprint().definition())
                      .as(
                          "%s/%s carries the blueprint file its manifest names",
                          bundle.key(), studio.key())
                      .contains("\"steps\""));
    }
  }

  @Test
  @DisplayName("a relative source is found by walking up from a service's own module directory")
  void aRelativeSourceIsFoundFromAModuleDirectory() {
    Workspace.require(REPOSITORY_ROOT, "PACK-BUNDLE");
    Path moduleDirectory =
        REPOSITORY_ROOT.resolve("orazaka-apps/services/orazaka-studio/orazaka-studio-service");

    // The production case exactly: `spring-boot:run` works from the module, the packs are at the
    // root. A resolver that only looked in the working directory would find nothing here.
    assertThat(resolverOn(moduleDirectory).discover())
        .isEqualTo(resolverOn(REPOSITORY_ROOT).discover());
  }

  @Test
  @DisplayName("the media toolkit resolves with its six Studios and its i18n overlay")
  void theMediaToolkitResolves() {
    Workspace.require(REPOSITORY_ROOT, "PACK-BUNDLE");
    PackBundleResolver resolver = resolverOn(REPOSITORY_ROOT);

    PackBundle media = resolver.read(REPOSITORY_ROOT.resolve("orazaka-packs/orazaka-media"));

    assertThat(media.kind()).isEqualTo(PackKind.TOOLKIT);
    assertThat(media.studios()).hasSize(6);
    assertThat(media.translations().keySet())
        .as("both locale files, read from i18n/ and keyed by file name")
        .containsExactlyInAnyOrder("en", "fr");
    assertThat(media.translations().get("fr").studios().get("image-generation").label())
        .as("the label the composer will render comes from the pack's own i18n")
        .isNotBlank();
  }

  @Test
  @DisplayName("a bundle whose blueprint file is missing is refused, naming the file")
  void aMissingBlueprintIsRefused(@TempDir Path packs) throws IOException {
    Path bundle = Files.createDirectories(packs.resolve("orazaka-packs").resolve("ghost"));
    Files.writeString(
        bundle.resolve("pack.yaml"),
        """
        apiVersion: orazaka.dev/v1
        key: ghost
        version: 1.0.0
        studios:
          - key: ghost-studio
            profession: general
            iconKey: studio
            pricing: FREE
            entitlementKey: studio.ghost-studio
            status: PUBLISHED
            publisherId: orazaka
            sortWeight: 0
            blueprint: studios/ghost-studio/blueprint.json
        """);

    assertThatThrownBy(() -> resolverOn(packs).read(bundle))
        .isInstanceOf(PackInstallException.class)
        .hasMessageContaining("studios/ghost-studio/blueprint.json");
  }

  @Test
  @DisplayName(
      "a manifest written against another grammar is refused by apiVersion, not guessed at")
  void anUnsupportedApiVersionIsRefused(@TempDir Path packs) throws IOException {
    Path bundle = Files.createDirectories(packs.resolve("orazaka-packs").resolve("future"));
    Files.writeString(
        bundle.resolve("pack.yaml"),
        """
        apiVersion: orazaka.dev/v2
        key: future
        version: 1.0.0
        studios: []
        """);

    assertThatThrownBy(() -> resolverOn(packs).read(bundle))
        .isInstanceOf(PackInstallException.class)
        .hasMessageContaining("orazaka.dev/v2");
  }

  @Test
  @DisplayName("a source that is not present here is skipped, not fatal")
  void anAbsentSourceIsSkipped(@TempDir Path elsewhere) {
    PackBundleResolver resolver =
        PackBundleResolver.resolvingFrom(
            MAPPER, new PackSourceProperties(List.of("no-such-directory")), elsewhere);

    assertThat(resolver.discover()).isEmpty();
  }
}
