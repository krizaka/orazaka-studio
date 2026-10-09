package com.krizaka.orazaka.studioservice.infrastructure.adapter.persistence;

import com.krizaka.orazaka.studio.domain.model.Blueprint;
import com.krizaka.orazaka.studio.domain.model.BlueprintStatus;
import java.time.Instant;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Exposes the pack-private {@code BlueprintMapper} to tests outside its package.
 *
 * <p>Lives here because the mapper is package-private, which is the point: production code reaches
 * it only through {@code JdbcBlueprintRepositoryAdapter}. A test that needs the parser directly
 * declares that need rather than widening the mapper's visibility for everyone (ERR-110).
 */
public final class BlueprintMapperBridge {

  private BlueprintMapperBridge() {}

  /**
   * Parses a blueprint through exactly the production path.
   *
   * @param studioKey the Studio
   * @param version the semver
   * @param status the publication status
   * @param definition the steps-and-outputs JSON
   * @param inputSchema the run-form JSON Schema
   * @param configSchemaDefaults install-time defaults
   * @param estimatedCredits what one run holds
   * @param changelog what changed
   * @param publishedAt when it went live
   * @return the validated blueprint
   */
  public static Blueprint toBlueprint(
      String studioKey,
      String version,
      BlueprintStatus status,
      JsonNode definition,
      String inputSchema,
      Map<String, String> configSchemaDefaults,
      long estimatedCredits,
      String changelog,
      Instant publishedAt) {
    return BlueprintMapper.toBlueprint(
        studioKey,
        version,
        status,
        definition,
        inputSchema,
        configSchemaDefaults,
        estimatedCredits,
        changelog,
        publishedAt);
  }
}
