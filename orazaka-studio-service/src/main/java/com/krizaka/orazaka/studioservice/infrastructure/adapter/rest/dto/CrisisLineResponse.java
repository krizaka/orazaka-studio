package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

import com.krizaka.orazaka.studio.domain.model.CrisisResource;

/**
 * One region's verified crisis line, as every client draws it.
 *
 * <p>Sent by the server so that no client holds a list of phone numbers of its own: a line that
 * changes is changed in the pack, once, with its source and the date it was read there.
 *
 * @param region the region code - {@code FR}, {@code BE-FR}, {@code BE-NL}, {@code CA}, {@code
 *     CA-QC}
 * @param label what the service calls itself
 * @param contact how to reach it, exactly as its own source writes it
 * @param availability when it answers
 * @param sourceUrl the official page it was read from
 * @param verifiedOn the day it was read there, ISO-8601
 */
public record CrisisLineResponse(
    String region,
    String label,
    String contact,
    String availability,
    String sourceUrl,
    String verifiedOn) {

  /**
   * @param resource the verified resource
   * @return its wire form
   */
  public static CrisisLineResponse from(CrisisResource resource) {
    return new CrisisLineResponse(
        resource.region(),
        resource.label(),
        resource.contact(),
        resource.availability(),
        resource.sourceUrl(),
        resource.verifiedOn().toString());
  }
}
