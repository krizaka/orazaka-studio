package com.krizaka.orazaka.studio.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    assertEquals(List.of("image"), config.value());
    assertEquals(20, config.bounds().maxItems());
  }

  @Test
  void anIntegerSettingRequiresNumericBounds() {
    assertThrows(
        IllegalArgumentException.class,
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
