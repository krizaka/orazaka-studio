package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConfigUpdateRequestTest {

  @Test
  @DisplayName("An absent body clears the configuration rather than failing")
  void nullConfigIsEmpty() {
    assertThat(new ConfigUpdateRequest(null).config()).isEmpty();
  }

  @Test
  @DisplayName("Config is copied at the boundary")
  void copiesConfig() {
    Map<String, String> source = new HashMap<>(Map.of("tone", "premium"));
    ConfigUpdateRequest request = new ConfigUpdateRequest(source);

    source.put("tone", "direct");

    assertThat(request.config().get("tone")).isEqualTo("premium");
  }
}
