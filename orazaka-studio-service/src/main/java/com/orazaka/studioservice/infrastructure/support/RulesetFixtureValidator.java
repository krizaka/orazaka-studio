package com.orazaka.studioservice.infrastructure.support;

import com.orazaka.studioservice.domain.exception.PackInstallException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A bundle whose ruleset carries a rule that cannot fail does not install.
 *
 * <p><b>Why at install and not in a test.</b> A ruleset is a suite of assertions pointed at the
 * user's document, and `FR-LEASE-001` checked that the word <i>bailleur</i> appears for a
 * requirement that is the identity of the parties — an assertion that runs, over a real subject,
 * and cannot fail, whose output told someone a BLOCKING legal requirement was met (ADR-072). The
 * pack's own Python suite now refuses that, which is enforcement for packs in this repository and
 * <b>discipline for everyone else</b>. A ruleset author may be outside this repository and cannot
 * be asked to remember (AGENTS.md §12), so the refusal belongs where a bundle becomes installable.
 *
 * <p><b>Why the engine may evaluate a pack's rules at all.</b> It does not interpret them: the pack
 * declares the pattern, the quantity and the two fixtures, and this runs the comparison it
 * declared. The check types are a closed, declarative set, so the mechanism is the platform's and
 * the subject is never ours — the same division as every other declaration the installer reads.
 *
 * <p><b>Superseded versions are exempt</b>, and the reason is the pack's own guarantee: a run is
 * replayed against the rules in force on its {@code asOf} date, so correcting a rule in an older
 * version would change a verdict already delivered. Only the newest file of each ruleset is judged.
 *
 * <p><b>{@code JUDGMENT} rules are exempt</b> and say so: their verdict comes from a model, and a
 * model call at install time would make this check as unreliable as the thing it checks.
 */
final class RulesetFixtureValidator {

  private static final String RULESETS_DIRECTORY = "rulesets";

  private RulesetFixtureValidator() {}

  /**
   * Refuses the bundle when a deterministic rule cannot distinguish its own fixture pair.
   *
   * @param bundleDirectory the directory holding {@code pack.yaml}
   * @param objectMapper the application's mapper
   * @throws PackInstallException naming every rule that does not discriminate
   */
  static void verify(Path bundleDirectory, ObjectMapper objectMapper) {
    Path rulesets = bundleDirectory.resolve(RULESETS_DIRECTORY);
    if (!Files.isDirectory(rulesets)) {
      return; // a pack that ships no ruleset has nothing to assert here
    }
    List<String> violations = new ArrayList<>();
    for (Path file : currentVersions(rulesets)) {
      JsonNode ruleset = read(file, objectMapper);
      for (JsonNode rule : ruleset.path("rules")) {
        if ("JUDGMENT".equals(rule.path("method").asString(""))) {
          continue;
        }
        String ruleId = rule.path("ruleId").asString("(unnamed rule)");
        JsonNode fixtures = rule.path("fixtures");
        if (!fixtures.has("pass") || !fixtures.has("fail")) {
          violations.add(ruleId + " ships no fixture pair");
          continue;
        }
        check(rule, fixtures.get("pass"), true, ruleId, violations);
        check(rule, fixtures.get("fail"), false, ruleId, violations);
      }
    }
    if (!violations.isEmpty()) {
      throw new PackInstallException(
          "Bundle at "
              + bundleDirectory
              + " ships rules that cannot fail, so their verdicts say nothing about a document:\n  "
              + String.join("\n  ", violations));
    }
  }

  /** One side of a rule's pair — a fragment, or several the rule claims it handles. */
  private static void check(
      JsonNode rule, JsonNode side, boolean expectPass, String ruleId, List<String> violations) {
    for (JsonNode fragment : side.isArray() ? side : List.of(side)) {
      boolean passed;
      try {
        passed = passes(rule.path("check"), fragment.asString(""));
      } catch (PatternSyntaxException unusable) {
        violations.add(
            ruleId + " has a pattern this engine cannot compile: " + unusable.getMessage());
        return;
      }
      if (passed != expectPass) {
        violations.add(
            ruleId
                + " expected "
                + (expectPass ? "PASS" : "FAIL")
                + " and returned "
                + (passed ? "PASS" : "FAIL")
                + " on: "
                + abbreviate(fragment.asString("")));
      }
    }
  }

  /**
   * The verdict a check produces, for the four declarative types.
   *
   * <p>Anything that is not a clean PASS — including the "cannot be evaluated" of a missing
   * quantity — counts as not passing here, because a fixture pair is about discrimination and a
   * rule that cannot evaluate its own positive fixture has not discriminated anything.
   */
  private static boolean passes(JsonNode check, String text) {
    return switch (check.path("type").asString("")) {
      case "REQUIRED_PATTERN" -> find(check.path("pattern").asString(""), text) != null;
      case "FORBIDDEN_PATTERN" -> find(check.path("pattern").asString(""), text) == null;
      case "NUMERIC_RATIO_MAX" -> ratioWithin(check, text);
      case "NUMERIC_MIN" -> atLeast(check, text);
      default -> false;
    };
  }

  private static boolean ratioWithin(JsonNode check, String text) {
    Matcher top = find(check.path("numerator").asString(""), text);
    Matcher bottom = find(check.path("denominator").asString(""), text);
    if (top == null || bottom == null) {
      return false;
    }
    double divisor = number(bottom.group(1));
    return divisor != 0 && number(top.group(1)) / divisor <= check.path("max").asDouble() + 1e-9;
  }

  private static boolean atLeast(JsonNode check, String text) {
    Matcher quantity = find(check.path("quantity").asString(""), text);
    if (quantity == null) {
      return false;
    }
    double value = worded(quantity.group(1));
    return !Double.isNaN(value) && value >= check.path("min").asDouble() - 1e-9;
  }

  /** French leases write a term in words as often as in digits, so both are read. */
  private static double worded(String raw) {
    return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
      case "un", "une" -> 1;
      case "deux" -> 2;
      case "trois" -> 3;
      case "quatre" -> 4;
      case "cinq" -> 5;
      case "six" -> 6;
      case "sept" -> 7;
      case "huit" -> 8;
      case "neuf" -> 9;
      case "dix" -> 10;
      case "douze" -> 12;
      default -> number(raw);
    };
  }

  private static double number(String raw) {
    String cleaned = raw.replaceAll("[\\s ]", "").replace(",", ".");
    try {
      return Double.parseDouble(cleaned);
    } catch (NumberFormatException notANumber) {
      return Double.NaN;
    }
  }

  private static Matcher find(String pattern, String text) {
    Matcher matcher = Pattern.compile(pattern).matcher(text);
    return matcher.find() ? matcher : null;
  }

  /** The newest version file of each ruleset; older ones are frozen so a replay stays identical. */
  private static List<Path> currentVersions(Path rulesets) {
    List<Path> newest = new ArrayList<>();
    try (Stream<Path> directories = Files.list(rulesets)) {
      for (Path directory : directories.filter(Files::isDirectory).sorted().toList()) {
        try (Stream<Path> versions = Files.list(directory)) {
          versions
              .filter(path -> path.getFileName().toString().endsWith(".json"))
              .max(Comparator.comparing(path -> path.getFileName().toString()))
              .ifPresent(newest::add);
        }
      }
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
    return newest;
  }

  private static JsonNode read(Path file, ObjectMapper objectMapper) {
    try {
      return objectMapper.readTree(Files.readString(file));
    } catch (IOException unreadable) {
      throw new PackInstallException("Ruleset " + file + " cannot be read", unreadable);
    }
  }

  private static String abbreviate(String text) {
    return text.length() <= 72 ? text : text.substring(0, 72) + "…";
  }
}
