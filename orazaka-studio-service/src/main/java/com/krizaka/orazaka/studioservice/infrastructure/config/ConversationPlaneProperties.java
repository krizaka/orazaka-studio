package com.krizaka.orazaka.studioservice.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Wiring of the conversation service's erasure surface ({@code
 * orazaka.studio-service.conversation-plane}): where the memory of a person's voice and listening
 * turns is erased on request (orazaka#301). Infrastructure only - an address.
 *
 * @param baseUrl the conversation service's base URL
 */
@ConfigurationProperties(prefix = "orazaka.studio-service.conversation-plane")
public record ConversationPlaneProperties(@DefaultValue("http://localhost:8080") String baseUrl) {

  /** Compact canonical constructor enforcing the wiring's invariants (ERR-106). */
  public ConversationPlaneProperties {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("conversation-plane base-url is required");
    }
  }
}
