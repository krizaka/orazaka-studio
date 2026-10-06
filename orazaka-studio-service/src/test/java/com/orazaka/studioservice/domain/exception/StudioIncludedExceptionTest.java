package com.orazaka.studioservice.domain.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StudioIncludedExceptionTest {

  @Test
  @DisplayName("[ADR-061] names the pack that already grants the Studio and says to run it")
  void namesTheToolkitAndTheRemedy() {
    StudioIncludedException thrown =
        new StudioIncludedException("image-generation", "studio.image-generation", "media");

    assertThat(thrown.studioKey()).isEqualTo("image-generation");
    assertThat(thrown.entitlementKey()).isEqualTo("studio.image-generation");
    assertThat(thrown.packKey()).isEqualTo("media");
    assertThat(thrown.getMessage()).contains("media").contains("run it directly");
  }
}
