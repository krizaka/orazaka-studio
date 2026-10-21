package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

import com.krizaka.orazaka.studioservice.domain.model.ConsentNotice;
import java.util.List;
import java.util.Map;

/**
 * The consent notice of a regulated pack: the statement in the caller's language, every language it
 * ships in, the regions it installs in with their verified crisis lines, and the retention the
 * system applies.
 *
 * @param packKey the pack
 * @param version the consent version in force - what an install must declare to agree
 * @param statement the statement in the requested language, verbatim
 * @param statements the statement in every language the pack ships, keyed by language
 * @param regions the regions the pack installs in, each with its verified crisis line
 * @param retentionDays the days a finished entry is kept at most
 */
public record ConsentNoticeResponse(
    String packKey,
    String version,
    String statement,
    Map<String, String> statements,
    List<CrisisLineResponse> regions,
    int retentionDays) {

  /**
   * @param notice the notice
   * @return its wire form
   */
  public static ConsentNoticeResponse from(ConsentNotice notice) {
    return new ConsentNoticeResponse(
        notice.packKey(),
        notice.version(),
        notice.statement(),
        notice.statements(),
        notice.regions().stream().map(CrisisLineResponse::from).toList(),
        notice.retentionDays());
  }
}
