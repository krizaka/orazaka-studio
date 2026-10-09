package com.krizaka.orazaka.studioservice.domain.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StudioNotFoundExceptionTest {

  @Test
  @DisplayName("Carries the key that resolved to nothing")
  void carriesTheKey() {
    StudioNotFoundException thrown = new StudioNotFoundException("nope");

    assertThat(thrown.studioKey()).isEqualTo("nope");
    assertThat(thrown.getMessage()).contains("nope");
  }
}
