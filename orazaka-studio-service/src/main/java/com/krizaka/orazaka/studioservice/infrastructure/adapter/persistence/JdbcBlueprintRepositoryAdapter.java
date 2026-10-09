package com.krizaka.orazaka.studioservice.infrastructure.adapter.persistence;

import com.krizaka.orazaka.studio.domain.model.Blueprint;
import com.krizaka.orazaka.studio.domain.model.BlueprintStatus;
import com.krizaka.orazaka.studioservice.domain.port.BlueprintRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads one blueprint version and parses it into the validated Tier-1 record.
 *
 * <p>Package-private adapter behind {@link BlueprintRepository} (AGENTS.md §2): the interpreter
 * depends on the port and never learns that a blueprint is stored as JSONB.
 */
@Component
class JdbcBlueprintRepositoryAdapter implements BlueprintRepository {

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  JdbcBlueprintRepositoryAdapter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper cannot be null");
  }

  @Override
  public Optional<Blueprint> find(String studioKey, String version) {
    return jdbcTemplate
        .query(
            "SELECT studio_key, version, status, definition, input_schema, config_schema,"
                + " estimated_credits, changelog, published_at"
                + " FROM studio_blueprint WHERE studio_key = ? AND version = ?",
            (rs, rowNum) -> read(rs),
            studioKey,
            version)
        .stream()
        .findFirst();
  }

  private Blueprint read(ResultSet rs) throws SQLException {
    java.sql.Timestamp publishedAt = rs.getTimestamp("published_at");
    return BlueprintMapper.toBlueprint(
        rs.getString("studio_key"),
        rs.getString("version"),
        BlueprintStatus.valueOf(rs.getString("status")),
        objectMapper.readTree(rs.getString("definition")),
        rs.getString("input_schema"),
        flattenDefaults(rs.getString("config_schema")),
        rs.getLong("estimated_credits"),
        rs.getString("changelog"),
        publishedAt == null ? null : publishedAt.toInstant());
  }

  /**
   * Flattens the config schema to its declared defaults.
   *
   * <p>The blueprint only needs "what does this key default to"; the full schema stays where the
   * install dialog reads it. A property with no default contributes nothing rather than an empty
   * string, so a missing answer stays distinguishable from a blank one.
   */
  private Map<String, String> flattenDefaults(String configSchema) {
    if (configSchema == null || configSchema.isBlank()) {
      return Map.of();
    }
    JsonNode schema = objectMapper.readTree(configSchema);
    Map<String, String> defaults = new LinkedHashMap<>();
    schema
        .propertyStream()
        .forEach(
            property -> {
              JsonNode fallback = property.getValue().path("default");
              if (!fallback.isMissingNode() && !fallback.isNull()) {
                defaults.put(property.getKey(), fallback.asString());
              }
            });
    return defaults;
  }
}
