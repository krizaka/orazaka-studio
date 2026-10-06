package com.orazaka.studioservice.infrastructure.support;

/**
 * Lets a listener invalidate a cached capability route without seeing how it is cached.
 *
 * <p>Shared plumbing between two adapters — the AMQP listener that hears a capability changed and
 * the HTTP adapter that holds the cache — which is what {@code infrastructure/support} is for
 * [ERR-130]. Without it the listener would need the package-private adapter, and closing that gap
 * would mean making the adapter public: an encapsulation leak [ERR-110] for one method.
 */
public interface CapabilityRouteCache {

  /**
   * Drops a capability's cached resolution so the next dispatch reads its current row.
   *
   * @param featureKey the capability whose row changed
   */
  void evict(String featureKey);
}
