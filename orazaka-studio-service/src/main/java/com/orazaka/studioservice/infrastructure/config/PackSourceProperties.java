package com.orazaka.studioservice.infrastructure.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where this deployment's packs come from ({@code orazaka.studio-service.packs}).
 *
 * <p>The service half of {@code ORAZAKA_PACKS_SOURCES} (ADR-049) — the one line that separates an
 * OSS deployment from a cloud one. The CLI already reads it to answer {@code orazaka pack list};
 * this record is what lets the service answer the question the CLI cannot: <b>what should already
 * be installed here</b>. Both read the same declaration, so an operator who adds a source sees it
 * in both places rather than in whichever one they remember to configure.
 *
 * <p><b>A relative source is resolved by walking up from the working directory</b>, because a
 * service started by {@code spring-boot:run} has its own module directory as its working directory
 * and the packs live at the repository root. That is a local-phase resolution (AGENTS.md §0) and it
 * is deliberately narrow: an absolute path is used exactly as given, which is what a container
 * deployment sets.
 *
 * <p><b>An empty list is the off switch.</b> A deployment that lists no source bootstraps nothing —
 * no flag was added for that, because a second key saying "but do not actually look there" is a way
 * for two declarations to disagree.
 *
 * @param sources the places packs are looked for, in priority order; a directory path, or {@code
 *     registry:<url>} for a registry this local phase cannot serve
 */
@ConfigurationProperties(prefix = "orazaka.studio-service.packs")
public record PackSourceProperties(@DefaultValue("orazaka-packs") List<String> sources) {

  private static final String REGISTRY_PREFIX = "registry:";

  /** Compact canonical constructor enforcing the wiring's invariants (ERR-106). */
  public PackSourceProperties {
    sources = sources == null ? List.of() : List.copyOf(sources);
  }

  /**
   * The sources this deployment can actually read.
   *
   * @return every directory source, in the order declared
   */
  public List<String> directories() {
    return sources.stream().filter(source -> !source.startsWith(REGISTRY_PREFIX)).toList();
  }

  /**
   * The sources this deployment has declared and cannot serve.
   *
   * <p>Returned rather than silently dropped: a deployment that thinks it has a registry and
   * quietly resolves nothing is worse than one that says the control plane is absent — the same
   * reason the CLI names them {@code unavailable} instead of skipping them.
   *
   * @return every registry source, in the order declared
   */
  public List<String> registries() {
    return sources.stream().filter(source -> source.startsWith(REGISTRY_PREFIX)).toList();
  }
}
