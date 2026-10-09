package com.krizaka.orazaka.studioservice.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.orazaka.studio.domain.model.PackKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The marketplace band, which is a decision about one actor and not a property of a pack. */
class PackAccessTest {

  @Test
  @DisplayName("a TOOLKIT is included only for an actor who is entitled to it")
  void aToolkitIsIncludedOnlyWhenEntitled() {
    assertThat(PackAccess.of(PackKind.TOOLKIT, true, 0)).isEqualTo(PackAccess.INCLUDED);
    // The defect this exists to end: kind alone said "included" to someone who has nothing.
    assertThat(PackAccess.of(PackKind.TOOLKIT, false, 4900)).isEqualTo(PackAccess.BUYABLE);
  }

  @Test
  @DisplayName("a VERTICAL is never included, however entitled — buying is how it is acquired")
  void aVerticalIsNeverIncluded() {
    assertThat(PackAccess.of(PackKind.VERTICAL, true, 4900)).isEqualTo(PackAccess.BUYABLE);
    assertThat(PackAccess.of(PackKind.VERTICAL, false, 4900)).isEqualTo(PackAccess.BUYABLE);
  }

  @Test
  @DisplayName("no price from billing is unavailable, never free and never included")
  void noPriceIsUnavailable() {
    assertThat(PackAccess.of(PackKind.VERTICAL, false, null)).isEqualTo(PackAccess.UNAVAILABLE);
    assertThat(PackAccess.of(PackKind.TOOLKIT, false, null)).isEqualTo(PackAccess.UNAVAILABLE);
    // Entitlement outranks a missing price: what the actor already has does not need one.
    assertThat(PackAccess.of(PackKind.TOOLKIT, true, null)).isEqualTo(PackAccess.INCLUDED);
  }
}
