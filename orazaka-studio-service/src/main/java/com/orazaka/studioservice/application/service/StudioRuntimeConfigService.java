package com.orazaka.studioservice.application.service;

import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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

  private static int parse(String raw, int defaultValue) {
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException malformed) {
      return defaultValue;
    }
  }
}
