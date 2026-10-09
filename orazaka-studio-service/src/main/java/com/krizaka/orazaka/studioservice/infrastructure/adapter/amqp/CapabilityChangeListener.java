package com.krizaka.orazaka.studioservice.infrastructure.adapter.amqp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.krizaka.orazaka.studioservice.infrastructure.support.CapabilityRouteCache;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Invalidates the cached route for a capability whose row changed.
 *
 * <p>Closes the gap ADR-037 reported and could not fix: the route cache had a TTL and nothing else,
 * because the config-change event the design assumed did not exist. Until it did, disabling a
 * capability left it dispatchable for up to the TTL — acceptable while the only cost is money,
 * unacceptable for a REGULATED pack that must stop the instant it is withdrawn (ADR-038).
 *
 * <p>Deliberately not deduplicated by {@code messageId}: evicting twice is free, and a dropped
 * eviction is the failure that matters. The TTL remains as the floor for exactly that case.
 */
@Component
class CapabilityChangeListener {

  private static final Logger logger = LoggerFactory.getLogger(CapabilityChangeListener.class);

  private final CapabilityRouteCache routeCache;

  CapabilityChangeListener(CapabilityRouteCache routeCache) {
    this.routeCache = Objects.requireNonNull(routeCache, "CapabilityRouteCache cannot be null");
  }

  /**
   * Applies one capability change.
   *
   * @param event which capability moved
   */
  @RabbitListener(queues = "orazaka.events.studio.capability")
  public void onCapabilityChanged(CapabilityChangedEvent event) {
    if (event == null || event.featureKey() == null) {
      return;
    }
    logger.info(
        "Capability {} changed (enabled={}) — invalidating its route",
        event.featureKey(),
        event.enabled());
    routeCache.evict(event.featureKey());
  }

  /**
   * Wire shape of {@code evt.capability.changed}; unknown fields ignored, as the contract allows.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CapabilityChangedEvent(String featureKey, Boolean enabled) {}
}
