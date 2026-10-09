package com.krizaka.orazaka.studioservice.domain.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InstallationNotFoundExceptionTest {

  @Test
  @DisplayName("Carries the id that resolved to nothing for this actor")
  void carriesTheId() {
    UUID id = UUID.fromString("9f1c0a10-0000-4000-8000-000000000010");

    assertThat(new InstallationNotFoundException(id).installationId()).isEqualTo(id);
  }
}
