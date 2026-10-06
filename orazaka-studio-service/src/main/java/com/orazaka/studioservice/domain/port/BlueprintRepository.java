package com.orazaka.studioservice.domain.port;

import com.orazaka.studio.domain.model.Blueprint;
import java.util.Optional;

/**
 * Outbound port: read one validated blueprint version.
 *
 * <p>Returns the Tier-1 {@link Blueprint} record, never raw JSON — the implementation parses at the
 * boundary so a graph that cannot be statically validated never reaches the interpreter (ERR-127).
 * The run path downstream therefore needs no defensive checks.
 */
public interface BlueprintRepository {

  /**
   * Loads one version.
   *
   * @param studioKey the Studio
   * @param version the semver of the version to load
   * @return the parsed, validated blueprint, or empty when that version does not exist
   */
  Optional<Blueprint> find(String studioKey, String version);
}
