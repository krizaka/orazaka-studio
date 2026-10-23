package com.krizaka.orazaka.studioservice.application.service;

import com.krizaka.orazaka.studio.domain.model.StudioRuntimeConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Post-startup behaviour an admin flips live, read from {@code studio_runtime_config}.
 *
 * <p>Limits are rows, never yaml (AGENTS.md §4, ADR-027/031): the fan-out cap and the step timeout
 * are exactly the knobs that need turning down at 2am when the accelerator is saturated, and a
 * redeploy is not an acceptable answer at that hour.
 *
 * <p>Every read falls back to a code default rather than failing. Deleting a row must revert
 * cleanly to a safe value, not break the run path (ADR-031).
 */
@Service
public class StudioRuntimeConfigService {

  private static final String SELECT_COLUMNS =
      "SELECT config_key, config_value, value_type, description, default_value, min_value,"
          + " max_value, max_items FROM studio_runtime_config";

  private final JdbcTemplate jdbcTemplate;

  public StudioRuntimeConfigService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
  }

  /**
   * Reads a numeric limit.
   *
   * @param key the config key
   * @param defaultValue the value to use when the row is absent or unparseable
   * @return the configured limit, or the default
   */
  public int intValue(String key, int defaultValue) {
    return jdbcTemplate
        .query(
            "SELECT config_value FROM studio_runtime_config WHERE config_key = ?",
            (rs, rowNum) -> rs.getString("config_value"),
            key)
        .stream()
        .findFirst()
        .map(raw -> parse(raw, defaultValue))
        .orElse(defaultValue);
  }

  /**
   * Reads a comma-separated list (Studio keys, for instance).
   *
   * @param key the config key
   * @return its trimmed, non-empty items; empty when the row is absent
   */
  public java.util.Set<String> listValue(String key) {
    return jdbcTemplate
        .query(
            "SELECT config_value FROM studio_runtime_config WHERE config_key = ?",
            (rs, rowNum) -> rs.getString("config_value"),
            key)
        .stream()
        .findFirst()
        .map(
            raw ->
                java.util.Arrays.stream(raw.split(","))
                    .map(String::trim)
                    .filter(item -> !item.isEmpty())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()))
        .orElse(java.util.Set.of());
  }

  /** Lists the settings and their operator-editable limits. */
  public List<StudioRuntimeConfig> all() {
    return jdbcTemplate.query(SELECT_COLUMNS + " ORDER BY config_key", rowMapper()).stream()
        .map(StudioRuntimeConfigService::toContract)
        .toList();
  }

  /**
   * Replaces a setting and appends its audit row in one transaction.
   *
   * @param key the existing setting
   * @param submitted the JSON value, whose shape must match the setting's type
   * @param actorId the authenticated administrator
   * @return the changed setting
   */
  @Transactional
  public StudioRuntimeConfig update(String key, JsonNode submitted, String actorId) {
    if (submitted == null) {
      throw new IllegalArgumentException("value is required");
    }
    if (actorId == null || actorId.isBlank()) {
      throw new IllegalArgumentException("actorId is required");
    }
    ConfigRow before =
        jdbcTemplate
            .query(SELECT_COLUMNS + " WHERE config_key = ? FOR UPDATE", rowMapper(), key)
            .stream()
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("unknown runtime config key: " + key));
    String afterValue = encode(submitted, before);
    jdbcTemplate.update(
        "UPDATE studio_runtime_config SET config_value = ? WHERE config_key = ?", afterValue, key);
    jdbcTemplate.update(
        "INSERT INTO studio_runtime_config_audit"
            + " (actor_id, config_key, before_value, after_value) VALUES (?, ?, ?, ?)",
        actorId,
        key,
        before.value(),
        afterValue);
    return toContract(before.withValue(afterValue));
  }

  private static String encode(JsonNode submitted, ConfigRow config) {
    return switch (config.type()) {
      case "int" -> {
        if (!submitted.isIntegralNumber() || !submitted.canConvertToInt()) {
          throw new IllegalArgumentException("value must be an integer");
        }
        long value = submitted.longValue();
        if (config.minimum() == null
            || config.maximum() == null
            || value < config.minimum()
            || value > config.maximum()) {
          throw new IllegalArgumentException(
              "value must be between " + config.minimum() + " and " + config.maximum());
        }
        yield Long.toString(value);
      }
      case "boolean" -> {
        if (!submitted.isBoolean()) {
          throw new IllegalArgumentException("value must be a boolean");
        }
        yield Boolean.toString(submitted.booleanValue());
      }
      case "list" -> {
        if (!submitted.isArray() || config.maxItems() == null) {
          throw new IllegalArgumentException("value must be an array of strings");
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : submitted) {
          if (!item.isTextual()) {
            throw new IllegalArgumentException("list values must be strings");
          }
          String text = item.textValue().trim();
          if (text.isEmpty() || text.contains(",") || items.contains(text)) {
            throw new IllegalArgumentException("list values must be unique, non-empty strings");
          }
          items.add(text);
        }
        if (items.size() > config.maxItems()) {
          throw new IllegalArgumentException(
              "list contains more than " + config.maxItems() + " items");
        }
        yield String.join(",", items);
      }
      default -> throw new IllegalStateException("unsupported runtime config type: " + config.type());
    };
  }

  private static StudioRuntimeConfig toContract(ConfigRow row) {
    Object value = decode(row.value(), row.type());
    Object defaultValue = decode(row.defaultValue(), row.type());
    return new StudioRuntimeConfig(
        row.key(),
        value,
        row.type(),
        row.description(),
        defaultValue,
        new StudioRuntimeConfig.Bounds(row.minimum(), row.maximum(), row.maxItems()));
  }

  private static Object decode(String raw, String type) {
    return switch (type) {
      case "int" -> Integer.valueOf(raw);
      case "boolean" -> {
        if (!"true".equals(raw) && !"false".equals(raw)) {
          throw new IllegalStateException("invalid boolean runtime config value");
        }
        yield Boolean.valueOf(raw);
      }
      case "list" ->
          java.util.Arrays.stream(raw.split(","))
              .map(String::trim)
              .filter(item -> !item.isEmpty())
              .toList();
      default -> throw new IllegalStateException("unsupported runtime config type: " + type);
    };
  }

  private static RowMapper<ConfigRow> rowMapper() {
    return (rs, rowNum) ->
        new ConfigRow(
            rs.getString("config_key"),
            rs.getString("config_value"),
            rs.getString("value_type"),
            rs.getString("description"),
            rs.getString("default_value"),
            rs.getObject("min_value", Long.class),
            rs.getObject("max_value", Long.class),
            rs.getObject("max_items", Integer.class));
  }

  private record ConfigRow(
      String key,
      String value,
      String type,
      String description,
      String defaultValue,
      Long minimum,
      Long maximum,
      Integer maxItems) {
    ConfigRow withValue(String updatedValue) {
      return new ConfigRow(
          key, updatedValue, type, description, defaultValue, minimum, maximum, maxItems);
    }
  }

  private static int parse(String raw, int defaultValue) {
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException malformed) {
      return defaultValue;
    }
  }
}
