package com.krizaka.orazaka.studio.domain.model;

import java.util.List;
import java.util.Objects;

/** The typed value and operator-editable bounds of one Studio runtime setting. */
public record StudioRuntimeConfig(
    String key, Object value, String type, String description, Object defaultValue, Bounds bounds) {

  public StudioRuntimeConfig {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("key must not be blank");
    }
    Objects.requireNonNull(description, "description must not be null");
    Objects.requireNonNull(bounds, "bounds must not be null");
    switch (SettingType.of(type)) {
      case INT -> requireInt(value, defaultValue, bounds);
      case BOOLEAN -> requireBoolean(value, defaultValue, bounds);
      case LIST -> {
        requireList(value, defaultValue, bounds);
        value = List.copyOf((List<?>) value);
        defaultValue = List.copyOf((List<?>) defaultValue);
      }
    }
  }

  /** The kinds of setting a Studio runtime config can hold, by the name stored in the database. */
  private enum SettingType {
    INT("int"),
    BOOLEAN("boolean"),
    LIST("list");

    private final String label;

    SettingType(String label) {
      this.label = label;
    }

    static SettingType of(String label) {
      for (SettingType candidate : values()) {
        if (candidate.label.equals(label)) {
          return candidate;
        }
      }
      throw new IllegalArgumentException("unsupported runtime config type: " + label);
    }
  }

  private static void requireInt(Object value, Object defaultValue, Bounds bounds) {
    if (!(value instanceof Integer)
        || !(defaultValue instanceof Integer)
        || bounds.minimum() == null
        || bounds.maximum() == null
        || bounds.maxItems() != null) {
      throw new IllegalArgumentException("int settings require integer values and bounds");
    }
  }

  private static void requireBoolean(Object value, Object defaultValue, Bounds bounds) {
    if (!(value instanceof Boolean)
        || !(defaultValue instanceof Boolean)
        || bounds.minimum() != null
        || bounds.maximum() != null
        || bounds.maxItems() != null) {
      throw new IllegalArgumentException("boolean settings require boolean values");
    }
  }

  private static void requireList(Object value, Object defaultValue, Bounds bounds) {
    if (!(value instanceof List<?> current)
        || !(defaultValue instanceof List<?> initial)
        || !current.stream().allMatch(String.class::isInstance)
        || !initial.stream().allMatch(String.class::isInstance)
        || bounds.maxItems() == null
        || bounds.minimum() != null
        || bounds.maximum() != null) {
      throw new IllegalArgumentException("list settings require string lists and item bounds");
    }
  }

  /** Limits that apply to the setting's value. */
  public record Bounds(Long minimum, Long maximum, Integer maxItems) {
    public Bounds {
      if (minimum != null && maximum != null && minimum > maximum) {
        throw new IllegalArgumentException("minimum must not exceed maximum");
      }
      if (maxItems != null && maxItems < 0) {
        throw new IllegalArgumentException("maxItems must not be negative");
      }
    }
  }
}
