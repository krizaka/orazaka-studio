package com.krizaka.orazaka.studio.domain.model;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * The consent a {@code REGULATED} pack requires before it may be installed.
 *
 * <p>Versioned, because consent is to a <i>statement</i> and not to a checkbox: when the statement
 * changes, the consent recorded against the old one no longer covers what the pack now does, and
 * the installation must ask again (GDPR Art. 9, Loi 25 — health data is a special category).
 *
 * <p>The version is the pack author's to bump, and bumping it is a decision with a cost: every
 * existing installation stops until its owner consents again. That cost is the point.
 *
 * <p>The statement is served in the caller's language by the server, never by a client: a legal
 * notice translated by whoever draws the screen is a notice nobody reviewed. {@link #statement} is
 * the platform's default language (French); {@link #statements} holds the other languages, one
 * reviewed text each, and each must say exactly what the system does.
 *
 * @param version the consent statement's version, {@code MAJOR.MINOR}
 * @param statement what the user is agreeing to, in plain language, in the default language
 * @param statements the same statement in other languages, keyed by language ({@code en}); empty
 *     when the pack ships the default language only
 */
public record PackConsent(String version, String statement, Map<String, String> statements) {

  /** The language of {@link #statement}. */
  public static final String DEFAULT_LANGUAGE = "fr";

  private static final Pattern VERSION = Pattern.compile("^\\d+\\.\\d+$");
  private static final Pattern LANGUAGE = Pattern.compile("^[a-z]{2}$");

  /**
   * A consent in the default language only.
   *
   * @param version the consent statement's version
   * @param statement the statement, in the default language
   */
  public PackConsent(String version, String statement) {
    this(version, statement, Map.of());
  }

  /** Compact canonical constructor enforcing the declaration's invariants (ERR-106). */
  public PackConsent {
    if (version == null || !VERSION.matcher(version).matches()) {
      throw new IllegalArgumentException("consent version must be MAJOR.MINOR, was: " + version);
    }
    if (statement == null || statement.isBlank()) {
      throw new IllegalArgumentException(
          "consent version " + version + " has no statement; consent to nothing is not consent");
    }
    statements = statements == null ? Map.of() : Map.copyOf(statements);
    statements.forEach(
        (language, text) -> {
          if (!LANGUAGE.matcher(language).matches() || text == null || text.isBlank()) {
            throw new IllegalArgumentException(
                "consent version "
                    + version
                    + " has a translation that is not a two-letter language with a text: "
                    + language);
          }
        });
  }

  /**
   * The statement in a caller's language.
   *
   * @param locale a BCP-47 tag ({@code en}, {@code en-CA}, {@code fr-FR}); {@code null} or an
   *     unknown language gives the default
   * @return the statement to show
   */
  public String statementFor(String locale) {
    if (locale == null || locale.length() < 2) {
      return statement;
    }
    String language = locale.substring(0, 2).toLowerCase(java.util.Locale.ROOT);
    return DEFAULT_LANGUAGE.equals(language)
        ? statement
        : statements.getOrDefault(language, statement);
  }

  /**
   * Every language this consent is available in, default included.
   *
   * @return language → statement
   */
  public Map<String, String> allStatements() {
    java.util.LinkedHashMap<String, String> all = new java.util.LinkedHashMap<>();
    all.put(DEFAULT_LANGUAGE, statement);
    all.putAll(statements);
    return Map.copyOf(all);
  }
}
