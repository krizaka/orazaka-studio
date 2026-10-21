package com.krizaka.orazaka.studioservice.domain.model;

import com.krizaka.orazaka.studio.domain.model.CrisisResource;
import java.util.List;
import java.util.Map;

/**
 * What a person reads before they agree to a regulated pack, served by the server in their language
 * (orazaka#301): the statement, the regions it installs in with each region's verified crisis line,
 * and the retention the system actually applies.
 *
 * @param packKey the pack
 * @param version the consent version now in force
 * @param statement the statement, in the requested language (the default language when the pack
 *     ships no translation)
 * @param statements the statement in every language the pack ships, keyed by language
 * @param regions the regions the pack installs in, each with its verified crisis line
 * @param retentionDays the days a finished run is kept at most, read from the platform's settings -
 *     the figure the statement states
 */
public record ConsentNotice(
    String packKey,
    String version,
    String statement,
    Map<String, String> statements,
    List<CrisisResource> regions,
    int retentionDays) {

  /** Compact canonical constructor: the notice is immutable. */
  public ConsentNotice {
    statements = Map.copyOf(statements);
    regions = List.copyOf(regions);
  }
}
