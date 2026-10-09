package com.krizaka.orazaka.studio.domain.model;

import java.util.Map;

/**
 * One locale's strings for a bundle: the pack's, its shelf's, and each of its Studios'.
 *
 * <p>Held per locale rather than per row so that adding a language to a pack is adding one file to
 * its bundle, which is the property that makes translation a contribution rather than a migration.
 *
 * @param pack the pack's own strings, {@code null} for a bundle with no catalog entry
 * @param category the shelf's strings, written only when this bundle creates the shelf
 * @param studios studio key → that Studio's strings; defensively copied
 */
public record PackTranslation(
    LocalisedText pack, LocalisedText category, Map<String, LocalisedText> studios) {

  /** Compact canonical constructor enforcing the translation's invariants (ERR-106). */
  public PackTranslation {
    studios = studios == null ? Map.of() : Map.copyOf(studios);
  }
}
