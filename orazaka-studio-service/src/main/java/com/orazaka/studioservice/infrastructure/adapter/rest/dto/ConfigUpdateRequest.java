package com.orazaka.studioservice.infrastructure.adapter.rest.dto;

import java.util.Map;

/**
 * A configuration replacement for an existing installation.
 *
 * <p>Wholesale replacement rather than a patch: a partial merge would make "clear this field"
 * unexpressible, and the install dialog always submits the complete form anyway.
 *
 * @param config the new answers, keyed by config-schema property; {@code null} is read as none
 */
public record ConfigUpdateRequest(Map<String, String> config) {

  /** Compact canonical constructor: absent configuration is empty, never null (ERR-106). */
  public ConfigUpdateRequest {
    config = config == null ? Map.of() : Map.copyOf(config);
  }
}
