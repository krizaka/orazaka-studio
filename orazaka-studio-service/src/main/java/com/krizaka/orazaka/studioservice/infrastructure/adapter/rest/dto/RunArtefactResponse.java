package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

import com.krizaka.orazaka.studioservice.domain.model.RunArtefact;
import java.util.Map;

/**
 * One produced artefact on the wire, with everything the client needs to render it.
 *
 * @param key the output's key
 * @param label the human label
 * @param type the rendering hint: {@code VIDEO}, {@code IMAGE}, {@code TEXT}
 * @param value what the run produced
 * @param labels the label in each language the pack wrote, by language
 */
public record RunArtefactResponse(
    String key, String label, String type, String value, Map<String, String> labels) {

  /**
   * Projects an artefact onto the wire.
   *
   * @param artefact the artefact
   * @return the response
   */
  public static RunArtefactResponse from(RunArtefact artefact) {
    return new RunArtefactResponse(
        artefact.key(), artefact.label(), artefact.type(), artefact.value(), artefact.labels());
  }
}
