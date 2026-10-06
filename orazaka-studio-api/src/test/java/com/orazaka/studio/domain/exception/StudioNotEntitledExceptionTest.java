package com.orazaka.studio.domain.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StudioNotEntitledExceptionTest {

  @Test
  void carriesThePackageToBuy_soTheRefusalOpensCheckoutRatherThanEndingTheFunnel() {
    StudioNotEntitledException thrown =
        new StudioNotEntitledException(
            "realestate-reels", "studio.realestate-reels", "realestate-studio");

    assertEquals("realestate-reels", thrown.studioKey());
    assertEquals("studio.realestate-reels", thrown.entitlementKey());
    assertEquals("realestate-studio", thrown.packKey());
    assertTrue(thrown.getMessage().contains("studio.realestate-reels"));
  }

  @Test
  void carriesNoPackage_whenNoPurchaseUnlocksIt() {
    // An INCLUDED studio on a plan that does not carry it: the remedy is a plan change,
    // not a checkout, and the UI must not offer one that does not exist.
    StudioNotEntitledException thrown =
        new StudioNotEntitledException("trade-showcase", "studio.tier.premium", null);

    assertNull(thrown.packKey());
  }
}
