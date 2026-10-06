package com.orazaka.studioservice.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orazaka.studio.domain.model.PackKind;
import com.orazaka.studio.domain.model.Studio;
import com.orazaka.studio.domain.model.StudioPricing;
import com.orazaka.studio.domain.model.StudioStatus;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StudioAccessTest {

  private static Studio studio(StudioPricing pricing, String packKey) {
    return new Studio(
        "trade-showcase",
        "Vitrine Artisan",
        null,
        "trades",
        "studio",
        null,
        pricing,
        packKey,
        PackKind.VERTICAL,
        "studio.trade-showcase",
        StudioStatus.PUBLISHED,
        "orazaka",
        "1.0.0",
        Set.of(),
        Instant.parse("2026-08-05T10:00:00Z"));
  }

  @Test
  @DisplayName("A granted entitlement opens any pricing mode")
  void grantedIsOpen() {
    assertThat(StudioAccess.of(studio(StudioPricing.PAID, "pkg"), true, true).locked()).isFalse();
    assertThat(StudioAccess.of(studio(StudioPricing.FREE, null), true, true).reason())
        .isEqualTo(LockReason.NONE);
  }

  @Test
  @DisplayName("An ungranted PAID studio locks with the pack to buy")
  void paidLocksWithPackage() {
    StudioAccess access =
        StudioAccess.of(studio(StudioPricing.PAID, "realestate-studio"), false, true);

    assertThat(access.locked()).isTrue();
    assertThat(access.reason()).isEqualTo(LockReason.REQUIRES_PURCHASE);
    assertThat(access.packKey()).isEqualTo("realestate-studio");
  }

  @Test
  @DisplayName("An ungranted INCLUDED studio locks as a plan gap, with nothing to buy")
  void includedLocksAsPlanGap() {
    StudioAccess access = StudioAccess.of(studio(StudioPricing.INCLUDED, null), false, true);

    assertThat(access.reason()).isEqualTo(LockReason.REQUIRES_PLAN);
    assertThat(access.packKey()).isNull();
  }

  @Test
  @DisplayName("An unresolved snapshot never refuses a FREE or INCLUDED studio")
  void unresolvedPassesThroughForNonPaid() {
    // Billing being unreachable says nothing about the actor. Refusing here would lock every
    // user out of Studios they already have, during an outage that is not their problem.
    assertThat(StudioAccess.of(studio(StudioPricing.FREE, null), false, false).locked()).isFalse();
    assertThat(StudioAccess.of(studio(StudioPricing.INCLUDED, null), false, false).locked())
        .isFalse();
  }

  @Test
  @DisplayName("An unresolved snapshot holds a PAID studio back as UNKNOWN, never as a refusal")
  void unresolvedHoldsPaidAsUnknown() {
    StudioAccess access = StudioAccess.of(studio(StudioPricing.PAID, "pkg"), false, false);

    assertThat(access.locked()).isTrue();
    assertThat(access.reason()).isEqualTo(LockReason.UNKNOWN);
  }

  @Test
  @DisplayName("locked and reason cannot disagree")
  void lockedAndReasonMustAgree() {
    assertThatThrownBy(() -> new StudioAccess(true, LockReason.NONE, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StudioAccess(false, LockReason.REQUIRES_PURCHASE, "pkg"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("open() is the unlocked decision")
  void openIsUnlocked() {
    assertThat(StudioAccess.open().locked()).isFalse();
    assertThat(StudioAccess.open().reason()).isEqualTo(LockReason.NONE);
  }
}
