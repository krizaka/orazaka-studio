package com.krizaka.orazaka.studioservice.domain.exception;

import java.io.Serial;
import java.util.UUID;

/** A webhook endpoint id names nothing <b>this actor owns</b> (same answer as "does not exist"). */
public class WebhookEndpointNotFoundException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * @param id the id that resolved to nothing for this actor
   */
  public WebhookEndpointNotFoundException(UUID id) {
    super("No such webhook endpoint: " + id);
  }
}
