package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

import java.util.Objects;

/**
 * The body of {@code POST /runs/{id}/send} (orazaka-studio#115).
 *
 * @param text what the actor says to the run that waits for input
 */
public record SendRequest(String text) {

  /** Compact canonical constructor: a message says something. */
  public SendRequest {
    Objects.requireNonNull(text, "text must not be null");
    if (text.isBlank()) {
      throw new IllegalArgumentException("text must not be blank");
    }
  }
}
