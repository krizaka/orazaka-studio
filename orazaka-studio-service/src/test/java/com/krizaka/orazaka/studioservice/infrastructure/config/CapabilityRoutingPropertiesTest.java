package com.krizaka.orazaka.studioservice.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CapabilityRoutingPropertiesTest {

  @Test
  void carriesTheAddressAndTheStalenessBound() {
    CapabilityRoutingProperties properties =
        new CapabilityRoutingProperties("http://localhost:8090", Duration.ofSeconds(30));

    assertEquals("http://localhost:8090", properties.baseUrl());
    assertEquals(Duration.ofSeconds(30), properties.cacheTtl());
  }

  @Test
  void rejects_aMissingAddress_becauseAServiceThatCannotAskCannotDispatch() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new CapabilityRoutingProperties("  ", Duration.ofSeconds(30)));
  }

  @Test
  void rejects_aNonPositiveTtl_whichWouldMakeEveryStepANetworkHop() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new CapabilityRoutingProperties("http://localhost:8090", Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CapabilityRoutingProperties("http://localhost:8090", Duration.ofSeconds(-1)));
  }
}
