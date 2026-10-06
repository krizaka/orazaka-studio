package com.orazaka.studioservice.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SessionJwtPropertiesTest {

  @Test
  @DisplayName("Accepts a 256-bit secret")
  void acceptsStrongSecret() {
    String secret = "orazaka-local-dev-identity-jwt-secret-256bit!";

    assertThat(new SessionJwtProperties(secret).secret()).isEqualTo(secret);
  }

  @Test
  @DisplayName("Rejects a secret too short for HS256 rather than signing weakly")
  void rejectsShortSecret() {
    assertThatThrownBy(() -> new SessionJwtProperties("too-short"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SessionJwtProperties(null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
