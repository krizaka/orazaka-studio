package com.orazaka.studioservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orazaka.billing.domain.model.EntitlementSnapshot;
import com.orazaka.billing.domain.port.EntitlementProvider;
import com.orazaka.studio.domain.exception.StudioNotEntitledException;
import com.orazaka.studio.domain.model.PackKind;
import com.orazaka.studio.domain.model.Studio;
import com.orazaka.studio.domain.model.StudioPricing;
import com.orazaka.studio.domain.model.StudioStatus;
import com.orazaka.studioservice.domain.model.LockReason;
import com.orazaka.studioservice.domain.model.StudioAccess;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StudioAccessServiceTest {

  private static final String ACTOR = "550e8400-e29b-41d4-a716-446655440002";

  private final EntitlementProvider entitlementProvider = mock(EntitlementProvider.class);
  private final StudioAccessService accessService = new StudioAccessService(entitlementProvider);

  private static Studio studio(String key, StudioPricing pricing, String packKey) {
    return studio(key, pricing, packKey, PackKind.VERTICAL);
  }

  private static Studio studio(String key, StudioPricing pricing, String packKey, PackKind kind) {
    return new Studio(
        key,
        "Label",
        null,
        "trades",
        "studio",
        null,
        pricing,
        packKey,
        kind,
        "studio." + key,
        StudioStatus.PUBLISHED,
        "orazaka",
        "1.0.0",
        Set.of(),
        Instant.parse("2026-08-05T10:00:00Z"));
  }

  private void grants(String... keys) {
    Map<String, String> matrix = new java.util.HashMap<>();
    for (String key : keys) {
      matrix.put(key, "true");
    }
    when(entitlementProvider.forActor(anyString()))
        .thenReturn(
            new EntitlementSnapshot(ACTOR, "premium", matrix, 5000, Instant.now().plusSeconds(60)));
  }

  @Test
  @DisplayName("A granted studio evaluates open")
  void grantedIsOpen() {
    grants("studio.trade-showcase");

    StudioAccess access =
        accessService.evaluate(studio("trade-showcase", StudioPricing.FREE, null), ACTOR);

    assertThat(access.locked()).isFalse();
  }

  @Test
  @DisplayName("An ungranted PAID studio evaluates locked with its package")
  void ungrantedPaidIsLocked() {
    grants();

    StudioAccess access =
        accessService.evaluate(studio("realestate-reels", StudioPricing.PAID, "pkg"), ACTOR);

    assertThat(access.reason()).isEqualTo(LockReason.REQUIRES_PURCHASE);
    assertThat(access.packKey()).isEqualTo("pkg");
  }

  @Test
  @DisplayName("A whole page is decided against ONE entitlement lookup, not one per card")
  void evaluatesAPageWithASingleLookup() {
    grants("studio.a");
    List<Studio> page =
        List.of(
            studio("a", StudioPricing.FREE, null),
            studio("b", StudioPricing.PAID, "pkg"),
            studio("c", StudioPricing.INCLUDED, null));

    Map<String, StudioAccess> decisions = accessService.evaluateAll(page, ACTOR);

    verify(entitlementProvider, times(1)).forActor(ACTOR);
    assertThat(decisions.get("a").locked()).isFalse();
    assertThat(decisions.get("b").reason()).isEqualTo(LockReason.REQUIRES_PURCHASE);
    assertThat(decisions.get("c").reason()).isEqualTo(LockReason.REQUIRES_PLAN);
  }

  @Test
  @DisplayName("requireEntitled throws with the pack so the refusal can open checkout")
  void requireEntitledCarriesThePackage() {
    grants();

    assertThatThrownBy(
            () ->
                accessService.requireEntitled(
                    studio("realestate-reels", StudioPricing.PAID, "realestate-studio"), ACTOR))
        .isInstanceOf(StudioNotEntitledException.class)
        .extracting(thrown -> ((StudioNotEntitledException) thrown).packKey())
        .isEqualTo("realestate-studio");
  }

  @Test
  @DisplayName("requireEntitled passes a granted studio through silently")
  void requireEntitledAllowsGranted() {
    grants("studio.trade-showcase");

    accessService.requireEntitled(studio("trade-showcase", StudioPricing.FREE, null), ACTOR);
  }

  @Test
  @DisplayName("An unresolved snapshot does not refuse an INCLUDED studio during a billing outage")
  void unresolvedDoesNotRefuseIncluded() {
    when(entitlementProvider.forActor(anyString()))
        .thenReturn(EntitlementSnapshot.unresolved(ACTOR, Instant.now().plusSeconds(5)));

    accessService.requireEntitled(studio("trade-showcase", StudioPricing.INCLUDED, null), ACTOR);
  }

  // ── ADR-061: the derived installation ────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "[ADR-061] an entitled actor has a TOOLKIT Studio — entitlement alone is the installation")
  void entitledToolkitIsIncluded() {
    grants("studio.image-generation");

    assertThat(
            accessService.isIncluded(
                studio("image-generation", StudioPricing.PAID, "media", PackKind.TOOLKIT), ACTOR))
        .isTrue();
  }

  @Test
  @DisplayName("[ADR-061] an unentitled actor does not have a TOOLKIT Studio")
  void unentitledToolkitIsNotIncluded() {
    grants("studio.something-else");

    assertThat(
            accessService.isIncluded(
                studio("image-generation", StudioPricing.PAID, "media", PackKind.TOOLKIT), ACTOR))
        .isFalse();
  }

  @Test
  @DisplayName(
      "[ADR-061] a VERTICAL is never included, however entitled — its installation is a row")
  void verticalIsNeverIncluded() {
    grants("studio.realestate-reels");

    assertThat(
            accessService.isIncluded(
                studio("realestate-reels", StudioPricing.PAID, "realestate", PackKind.VERTICAL),
                ACTOR))
        .isFalse();
  }
}
