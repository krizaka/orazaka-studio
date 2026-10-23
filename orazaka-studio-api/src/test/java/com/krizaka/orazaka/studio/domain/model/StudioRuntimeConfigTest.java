package com.krizaka.orazaka.studio.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StudioRuntimeConfigTest {

  @Test
  void aListSettingCopiesItsValuesAndCarriesTheItemBound() {
    List<String> studios = new ArrayList<>(List.of("image"));
    var config =
        new StudioRuntimeConfig(
            "run.result-cache.studios",
            studios,
            "list",
            "Cached Studios",
            List.of(),
            new StudioRuntimeConfig.Bounds(null, null, 20));

    studios.add("video");

    assertThat(config.value()).isEqualTo(List.of("image"));
    assertThat(config.bounds().maxItems()).isEqualTo(20);
  }

  @Test
  void anIntegerSettingRequiresNumericBounds() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new StudioRuntimeConfig(
                    "run.fan-out-max",
                    10,
                    "int",
                    "Fan-out cap",
                    10,
                    new StudioRuntimeConfig.Bounds(null, null, null)));
  }
}
