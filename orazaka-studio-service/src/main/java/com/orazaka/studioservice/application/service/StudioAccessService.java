package com.orazaka.studioservice.application.service;

import com.orazaka.billing.domain.model.EntitlementSnapshot;
import com.orazaka.billing.domain.port.EntitlementProvider;
import com.orazaka.studio.domain.exception.StudioNotEntitledException;
import com.orazaka.studio.domain.model.PackKind;
import com.orazaka.studio.domain.model.Studio;
import com.orazaka.studioservice.domain.model.StudioAccess;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * The single place that answers "may this actor install this Studio?".
 *
 * <p>Read twice, on purpose (ADR-034 §8.3): cosmetically by the catalogue, authoritatively by the
 * install and run paths. One implementation rather than two, because a catalogue that greys a
 * Studio the install path would accept — or the reverse — is worse than either behaviour alone.
 *
 * <p>Entitlement gates <b>access</b>; credits gate <b>volume</b>. This service never looks at a
 * balance: an actor with no credits is entitled and broke, which is a different screen from an
 * actor who has not bought the product.
 */
@Service
public class StudioAccessService {

  private final EntitlementProvider entitlementProvider;

  public StudioAccessService(EntitlementProvider entitlementProvider) {
    this.entitlementProvider =
        Objects.requireNonNull(entitlementProvider, "EntitlementProvider cannot be null");
  }

  /**
   * Decides access for one Studio.
   *
   * @param studio the Studio being evaluated
   * @param actorId the opaque billable subject
   * @return the decision, carrying the pack to buy when one applies
   */
  public StudioAccess evaluate(Studio studio, String actorId) {
    EntitlementSnapshot snapshot = entitlementProvider.forActor(actorId);
    return StudioAccess.of(studio, snapshot.allows(studio.entitlementKey()), snapshot.resolved());
  }

  /**
   * Decides access for a whole catalogue page against a single snapshot.
   *
   * <p>One lookup for the page rather than one per card: the catalogue is the most-hit endpoint in
   * the feature and an entitlement call per row would turn a browse into forty service hops.
   *
   * @param studios the page being rendered
   * @param actorId the opaque billable subject
   * @return each Studio's decision, keyed by Studio key
   */
  public Map<String, StudioAccess> evaluateAll(Iterable<Studio> studios, String actorId) {
    EntitlementSnapshot snapshot = entitlementProvider.forActor(actorId);
    java.util.Map<String, StudioAccess> decisions = new java.util.LinkedHashMap<>();
    for (Studio studio : studios) {
      decisions.put(
          studio.studioKey(),
          StudioAccess.of(studio, snapshot.allows(studio.entitlementKey()), snapshot.resolved()));
    }
    return Map.copyOf(decisions);
  }

  /**
   * Whether this actor has this Studio <b>without an installation row</b> — the derived
   * installation of a TOOLKIT (ADR-061).
   *
   * <p>Entitlement alone is the installation: no {@code studio_installation} row is read or ever
   * written, so "the plan grants it but it is not installed" is not a state a TOOLKIT can be in. A
   * VERTICAL is never included, however entitled — its installation is a row the actor creates.
   *
   * <p>The same decision as {@link #evaluate}, read once more: a TOOLKIT an actor cannot open is
   * not included, and the refusal they then receive is {@link #requireEntitled}'s, carrying the
   * pack.
   *
   * @param studio the Studio being evaluated
   * @param actorId the opaque billable subject
   * @return {@code true} for a TOOLKIT Studio the actor is entitled to
   */
  public boolean isIncluded(Studio studio, String actorId) {
    return studio.kind() == PackKind.TOOLKIT && !evaluate(studio, actorId).locked();
  }

  /**
   * Enforces access, for the paths where the answer is authoritative rather than cosmetic.
   *
   * @param studio the Studio being installed or run
   * @param actorId the opaque billable subject
   * @throws StudioNotEntitledException when the actor is not entitled — carrying the pack to buy so
   *     the refusal opens checkout instead of ending the funnel
   */
  public void requireEntitled(Studio studio, String actorId) {
    StudioAccess access = evaluate(studio, actorId);
    if (access.locked()) {
      throw new StudioNotEntitledException(
          studio.studioKey(), studio.entitlementKey(), access.packKey());
    }
  }
}
