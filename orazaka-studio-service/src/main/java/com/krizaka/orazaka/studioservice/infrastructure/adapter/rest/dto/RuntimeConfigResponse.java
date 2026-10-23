package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.krizaka.orazaka.studio.domain.model.StudioRuntimeConfig;

/** HTTP shape of a runtime setting. */
public record RuntimeConfigResponse(
    String key,
    Object value,
    String type,
    String description,
    @JsonProperty("default") Object defaultValue,
    StudioRuntimeConfig.Bounds bounds) {

  public static RuntimeConfigResponse from(StudioRuntimeConfig config) {
    return new RuntimeConfigResponse(
        config.key(),
        config.value(),
        config.type(),
        config.description(),
        config.defaultValue(),
        config.bounds());
  }
}
