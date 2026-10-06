package com.orazaka.studioservice.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StudioDataSourcePropertiesTest {

  private static final String URL = "jdbc:postgresql://localhost:5432/orazaka_studio_db";

  @Test
  @DisplayName("Accepts the service's own database wiring")
  void acceptsOwnDatabase() {
    var properties = new StudioDataSourceProperties(URL, "orazaka_studio", "secret");

    assertThat(properties.username()).isEqualTo("orazaka_studio");
  }

  @Test
  @DisplayName("Rejects a missing url, username or password at bootstrap")
  void rejectsIncompleteWiring() {
    assertThatThrownBy(() -> new StudioDataSourceProperties(null, "u", "p"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new StudioDataSourceProperties(URL, null, "p"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new StudioDataSourceProperties(URL, "u", null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("Rejects a non-postgres url — a silently wrong driver is worse than no boot")
  void rejectsForeignDriver() {
    assertThatThrownBy(() -> new StudioDataSourceProperties("jdbc:h2:mem:studio", "u", "p"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
