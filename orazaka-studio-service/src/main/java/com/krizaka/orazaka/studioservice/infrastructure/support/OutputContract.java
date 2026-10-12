package com.krizaka.orazaka.studioservice.infrastructure.support;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Checks a run's {@code JSON} output against the contract its blueprint declares (ADR-076).
 *
 * <p><b>A deliberately small subset of JSON Schema, and it says so.</b> The keywords below are the
 * ones this class enforces; a schema naming any other is refused when the blueprint is loaded
 * ({@link #unsupportedKeywords}), because a keyword silently ignored is a contract that reads
 * stricter than it is — a {@code pattern} nobody checks is a promise nobody keeps. No library: the
 * studio service has no other JSON Schema need, and a twenty-keyword dependency to enforce eight is
 * a larger surface than the eight.
 *
 * <p>Violations name the <b>path and the rule</b>, never the value: the value is what a person
 * said, in a REGULATED pack, and the violation becomes a run's error message.
 *
 * <p>Dependency-free pure functions over a third-party type ([ERR-127] (b)).
 */
public final class OutputContract {

  /** Every keyword {@link #violations} enforces. Anything else is refused at load. */
  static final Set<String> SUPPORTED =
      Set.of(
          "type",
          "required",
          "properties",
          "additionalProperties",
          "items",
          "minItems",
          "maxItems",
          "minLength",
          "maxLength",
          "minimum",
          "maximum",
          "enum",
          // Annotations: they change nothing a value must satisfy.
          "title",
          "description",
          "$schema");

  private OutputContract() {}

  /**
   * The keywords of a schema this class would not enforce, at any depth.
   *
   * @param schema the declared schema
   * @return the unsupported keywords, empty when the schema is fully enforceable
   */
  public static List<String> unsupportedKeywords(JsonNode schema) {
    List<String> unsupported = new ArrayList<>();
    collectUnsupported(schema, unsupported);
    return unsupported;
  }

  private static void collectUnsupported(JsonNode schema, List<String> unsupported) {
    if (schema == null || !schema.isObject()) {
      return;
    }
    schema
        .propertyNames()
        .forEach(
            keyword -> {
              if (!SUPPORTED.contains(keyword)) {
                unsupported.add(keyword);
              }
            });
    JsonNode properties = schema.path("properties");
    if (properties.isObject()) {
      properties.values().forEach(child -> collectUnsupported(child, unsupported));
    }
    collectUnsupported(schema.path("items"), unsupported);
    JsonNode additional = schema.path("additionalProperties");
    if (additional.isObject()) {
      collectUnsupported(additional, unsupported);
    }
  }

  /**
   * What the value breaks of the contract.
   *
   * @param schema the declared schema
   * @param value the produced value
   * @return one line per violation — a path and a rule, never the value; empty when it conforms
   */
  public static List<String> violations(JsonNode schema, JsonNode value) {
    List<String> found = new ArrayList<>();
    check(schema, value, "$", found);
    return found;
  }

  /**
   * Lifts the object out of a model's answer that wrapped it in a Markdown code fence.
   *
   * <p>A fence around the whole answer is packaging, not content: it is removed only when it is the
   * first and the last thing in the text, so an answer that says something before or after its
   * object stays unparseable and fails its contract, which is the honest outcome.
   *
   * @param raw the produced text
   * @return the text inside a surrounding fence, or the text itself
   */
  public static String unfenced(String raw) {
    String trimmed = raw.strip();
    if (!trimmed.startsWith("```") || !trimmed.endsWith("```") || trimmed.length() < 6) {
      return trimmed;
    }
    int firstLineEnd = trimmed.indexOf('\n');
    if (firstLineEnd < 0) {
      return trimmed;
    }
    return trimmed.substring(firstLineEnd + 1, trimmed.length() - 3).strip();
  }

  private static void check(JsonNode schema, JsonNode value, String path, List<String> found) {
    if (schema == null || !schema.isObject()) {
      return;
    }
    String type = schema.path("type").asString(null);
    if (type != null && !hasType(value, type)) {
      found.add(path + ": must be " + type);
      return;
    }
    JsonNode allowed = schema.path("enum");
    if (allowed.isArray() && !contains(allowed, value)) {
      found.add(path + ": must be one of the declared values");
    }
    if (value.isString()) {
      int length = value.asString().codePointCount(0, value.asString().length());
      bound(schema, "minLength", length, path, found, true);
      bound(schema, "maxLength", length, path, found, false);
    }
    if (value.isNumber()) {
      if (schema.has("minimum") && value.asDouble() < schema.path("minimum").asDouble()) {
        found.add(path + ": below minimum");
      }
      if (schema.has("maximum") && value.asDouble() > schema.path("maximum").asDouble()) {
        found.add(path + ": above maximum");
      }
    }
    if (value.isArray()) {
      bound(schema, "minItems", value.size(), path, found, true);
      bound(schema, "maxItems", value.size(), path, found, false);
      JsonNode items = schema.path("items");
      for (int i = 0; i < value.size(); i++) {
        check(items, value.get(i), path + "[" + i + "]", found);
      }
    }
    if (value.isObject()) {
      checkObject(schema, value, path, found);
    }
  }

  private static void checkObject(
      JsonNode schema, JsonNode value, String path, List<String> found) {
    JsonNode required = schema.path("required");
    if (required.isArray()) {
      required.forEach(
          name -> {
            if (!value.has(name.asString())) {
              found.add(path + "." + name.asString() + ": required");
            }
          });
    }
    JsonNode properties = schema.path("properties");
    boolean closed =
        schema.path("additionalProperties").isBoolean()
            && !schema.path("additionalProperties").asBoolean();
    Iterator<String> names = value.propertyNames().iterator();
    while (names.hasNext()) {
      String name = names.next();
      JsonNode declared = properties.path(name);
      if (declared.isMissingNode()) {
        if (closed) {
          found.add(path + "." + name + ": not declared");
        }
        continue;
      }
      check(declared, value.get(name), path + "." + name, found);
    }
  }

  private static void bound(
      JsonNode schema, String keyword, int actual, String path, List<String> found, boolean min) {
    if (!schema.has(keyword)) {
      return;
    }
    int limit = schema.path(keyword).asInt();
    if (min ? actual < limit : actual > limit) {
      found.add(path + ": " + keyword + " " + limit);
    }
  }

  private static boolean hasType(JsonNode value, String type) {
    return switch (type) {
      case "object" -> value.isObject();
      case "array" -> value.isArray();
      case "string" -> value.isString();
      case "integer" -> value.isIntegralNumber();
      case "number" -> value.isNumber();
      case "boolean" -> value.isBoolean();
      case "null" -> value.isNull();
      default -> false;
    };
  }

  private static boolean contains(JsonNode allowed, JsonNode value) {
    for (JsonNode candidate : allowed) {
      if (candidate.equals(value)) {
        return true;
      }
    }
    return false;
  }
}
