package com.orazaka.studioservice.domain.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StudioNotInstalledExceptionTest {

  @Test
  @DisplayName(
      "[ADR-061] carries what an entitlement refusal carries, so one client branch reads both")
  void carriesTheSameFieldsAsTheEntitlementRefusal() {
    StudioNotInstalledException thrown =
        new StudioNotInstalledException(
            "realestate-reels", "studio.realestate-reels", "realestate");

    assertThat(thrown.studioKey()).isEqualTo("realestate-reels");
    assertThat(thrown.entitlementKey()).isEqualTo("studio.realestate-reels");
    assertThat(thrown.packKey()).isEqualTo("realestate");
    assertThat(thrown.getMessage()).contains("realestate-reels");
  }
}
