package com.orazaka.studioservice.infrastructure.adapter.rest.dto;

import java.util.List;

/**
 * What a validation found.
 *
 * <p>Returns the problems rather than a status code so the CLI can print all of them at once. A
 * bundle with four unresolvable capabilities should cost one round trip to diagnose, not four.
 *
 * @param packKey the bundle checked
 * @param version the bundle's version
 * @param valid whether it is installable on this platform
 * @param problems every problem found, in the order found; defensively copied
 */
public record PackValidationResponse(
    String packKey, String version, boolean valid, List<String> problems) {

  /** Compact canonical constructor enforcing the response's invariants (ERR-106). */
  public PackValidationResponse {
    problems = problems == null ? List.of() : List.copyOf(problems);
  }
}
