package com.krizaka.orazaka.studioservice.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.krizaka.orazaka.studio.domain.model.BlueprintStatus;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BlueprintVersionTest {

  @Test
  @DisplayName("A draft carries no publication date")
  void draftHasNoPublishDate() {
    var version =
        new BlueprintVersion("trade-showcase", "1.1.0", BlueprintStatus.DRAFT, 80, null, null);

    assertThat(version.publishedAt()).isNull();
    assertThat(version.status()).isEqualTo(BlueprintStatus.DRAFT);
  }

  @Test
  @DisplayName("A published version carries its changelog — an explicit upgrade must be readable")
  void publishedCarriesChangelog() {
    var version =
        new BlueprintVersion(
            "trade-showcase",
            "1.0.0",
            BlueprintStatus.PUBLISHED,
            80,
            "Vitrine initiale",
            Instant.parse("2026-08-05T10:00:00Z"));

    assertThat(version.changelog()).isEqualTo("Vitrine initiale");
  }

  @Test
  @DisplayName("Rejects blank identity or a negative estimate")
  void rejectsInvalidIdentity() {
    assertThatThrownBy(
            () -> new BlueprintVersion(" ", "1.0.0", BlueprintStatus.DRAFT, 0, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BlueprintVersion("k", "", BlueprintStatus.DRAFT, 0, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BlueprintVersion("k", "1.0.0", null, 0, null, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new BlueprintVersion("k", "1.0.0", BlueprintStatus.DRAFT, -1, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
