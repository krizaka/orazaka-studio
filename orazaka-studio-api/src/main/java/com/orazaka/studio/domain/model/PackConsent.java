package com.orazaka.studio.domain.model;

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
 * @param version the consent statement's version, {@code MAJOR.MINOR}
 * @param statement what the user is agreeing to, in plain language
 */
public record PackConsent(String version, String statement) {

  private static final Pattern VERSION = Pattern.compile("^\\d+\\.\\d+$");

  /** Compact canonical constructor enforcing the declaration's invariants (ERR-106). */
  public PackConsent {
    if (version == null || !VERSION.matcher(version).matches()) {
      throw new IllegalArgumentException("consent version must be MAJOR.MINOR, was: " + version);
    }
    if (statement == null || statement.isBlank()) {
      throw new IllegalArgumentException(
          "consent version " + version + " has no statement; consent to nothing is not consent");
    }
  }
}
