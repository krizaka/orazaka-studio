package com.krizaka.orazaka.studioservice.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StudioUsageTest {

  private static StudioUsage usage(long installations, long runs, long succeeded, long failed) {
    return new StudioUsage("trade-showcase", "Vitrine", installations, runs, succeeded, failed, 80);
  }

  @Test
  @DisplayName("Success rate is the share of runs that finished green")
  void successRateIsTheGreenShare() {
    assertThat(usage(4, 10, 7, 3).successRate()).isEqualTo(0.7d);
  }

  @Test
  @DisplayName("A Studio nobody has run scores 0, not a perfect score")
  void neverRunIsZeroNotPerfect() {
    // A division guard that returned 1.0 would make the least-proven Studio look like the best one.
    assertThat(usage(3, 0, 0, 0).successRate()).isZero();
  }

  @Test
  @DisplayName("Installed and never run is the churn signal, not an empty catalogue row")
  void installedButUnusedIsTheChurnSignal() {
    assertThat(usage(3, 0, 0, 0).installedButUnused()).isTrue();
    assertThat(usage(3, 1, 1, 0).installedButUnused()).isFalse();
    assertThat(usage(0, 0, 0, 0).installedButUnused()).isFalse();
  }

  @Test
  @DisplayName("Rejects a row that names no Studio")
  void rejectsAnonymousRows() {
    assertThatThrownBy(() -> new StudioUsage(" ", "L", 0, 0, 0, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
