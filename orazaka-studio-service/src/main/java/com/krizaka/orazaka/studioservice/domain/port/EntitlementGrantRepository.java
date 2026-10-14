package com.krizaka.orazaka.studioservice.domain.port;

import java.util.Set;

/**
 * Outbound port: the Studio entitlements an <b>operator</b> granted an actor directly, outside any
 * plan.
 *
 * <p>Billing answers "what did this actor buy"; this answers "what did the operator of this
 * platform give this actor" — a first-party product calling Orazaka with its own service account
 * (Yomizaka runs the document Studios), a pilot customer, an environment where nothing is sold
 * (dev, {@code BILLING_ENABLED=false}). A grant is data — a row with its reason and its expiry,
 * granted and revoked in the database, never in configuration or code (ADR-031).
 *
 * <p>It only ever <b>adds</b>: an actor with no grant is decided by billing alone, exactly as
 * before, and a grant never locks anything.
 */
public interface EntitlementGrantRepository {

  /**
   * The entitlement keys granted to an actor and in force now.
   *
   * @param actorId the opaque billable subject
   * @return the granted keys ({@code studio.<studioKey>}), empty when there is none
   */
  Set<String> grantedKeys(String actorId);
}
