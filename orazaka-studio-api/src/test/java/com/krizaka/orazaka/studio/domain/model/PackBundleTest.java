package com.krizaka.orazaka.studio.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The catalogue invariants, pinned where they now live.
 *
 * <p>Until phase D these were checked by {@code PackCoherenceRules} reading {@code infra/initdb}.
 * The content moved into bundles, which that rule cannot see, so the invariants moved into these
 * constructors — from "a build rule notices" to "the value cannot be constructed". These tests are
 * what make that claim checkable rather than a comment.
 */
class PackBundleTest {

  private static PackBlueprint blueprint() {
    return new PackBlueprint(
        "1.0.0", BlueprintStatus.PUBLISHED, "{\"steps\":[]}", "{}", "{}", 100, "initial", "system");
  }

  private static PackStudio studio(String key, StudioPricing pricing) {
    return new PackStudio(
        key,
        "trades",
        "studio",
        pricing,
        "studio." + key,
        StudioStatus.PUBLISHED,
        "orazaka",
        10,
        blueprint());
  }

  private static PackBundle bundle(
      String key, PackCatalogEntry catalog, PackPricing pricing, PackStudio... studios) {
    return new PackBundle(
        PackBundle.API_VERSION,
        key,
        "1.0.0",
        PackTier.DATA,
        PackDistribution.OSS,
        RegulatoryClass.STANDARD,
        PackKind.VERTICAL,
        List.of(),
        catalog,
        pricing,
        List.of(studios),
        Map.of(),
        null,
        null,
        null);
  }

  @Test
  @DisplayName("a bundle with no catalog entry ships Studios only — the trade-showcase case")
  void studioOnlyBundleIsValid() {
    PackBundle free =
        bundle("trade-showcase", null, null, studio("trade-showcase", StudioPricing.FREE));

    assertFalse(free.isCatalogued(), "no catalog entry means no pack row is written");
    assertEquals(1, free.studios().size());
  }

  @Test
  @DisplayName("[ADR-036 #3] a catalogued pack must carry a price, or its grants can never exist")
  void cataloguedPackWithoutPricingIsRefused() {
    PackCatalogEntry catalog =
        new PackCatalogEntry("business", "studio", PackStatus.PUBLISHED, 100, null);

    IllegalArgumentException refused =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                bundle("paid-pack", catalog, null, studio("some-studio", StudioPricing.INCLUDED)));

    assertTrue(refused.getMessage().contains("declares no pricing"), refused.getMessage());
  }

  @Test
  @DisplayName("a PAID Studio without a catalog entry has nothing to open a checkout for")
  void paidStudioWithoutCatalogIsRefused() {
    IllegalArgumentException refused =
        assertThrows(
            IllegalArgumentException.class,
            () -> bundle("orphan", null, null, studio("paid-studio", StudioPricing.PAID)));

    assertTrue(refused.getMessage().contains("PAID Studio"), refused.getMessage());
  }

  @Test
  @DisplayName("a Studio's entitlement key must be studio.<key>, so a grant cannot unlock another")
  void mismatchedEntitlementKeyIsRefused() {
    IllegalArgumentException refused =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new PackStudio(
                    "reels",
                    "sales",
                    "studio",
                    StudioPricing.FREE,
                    "studio.something-else",
                    StudioStatus.PUBLISHED,
                    "orazaka",
                    0,
                    blueprint()));

    assertTrue(refused.getMessage().contains("studio.reels"), refused.getMessage());
  }

  @Test
  @DisplayName("a bundle shipping no Studio is a packaging error, not a pack")
  void emptyBundleIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> bundle("empty", null, null));
  }

  @Test
  @DisplayName("a manifest written against another grammar is refused, never guessed at")
  void unknownApiVersionIsRefused() {
    IllegalArgumentException refused =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new PackBundle(
                    "orazaka.dev/v2",
                    "future",
                    "1.0.0",
                    PackTier.DATA,
                    PackDistribution.OSS,
                    RegulatoryClass.STANDARD,
                    PackKind.VERTICAL,
                    List.of(),
                    null,
                    null,
                    List.of(studio("future-studio", StudioPricing.FREE)),
                    Map.of(),
                    null,
                    null,
                    null));

    assertTrue(refused.getMessage().contains("orazaka.dev/v2"), refused.getMessage());
  }

  @Test
  @DisplayName("a capability declaring one discriminant but not the other is refused")
  void capabilityNeedsBothDiscriminants() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PackCapability(
                "orazaka.doc.extract",
                "not-a-routing-key",
                "doc.extract",
                "DOCUMENT_PAGE",
                "CHAT",
                "{\"type\":\"object\",\"properties\":{}}",
                "{\"type\":\"object\",\"properties\":{}}",
                true));
  }

  @Test
  @DisplayName("[ADR-051] a SENSITIVE pack with no scope guard cannot be constructed at all")
  void sensitiveWithoutAScopeGuardIsRefused() {
    // The publish gate, at the boundary the manifest crosses: refusing here means validate,
    // install and publish all refuse, without any of them carrying the rule.
    IllegalArgumentException refused =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new PackBundle(
                    PackBundle.API_VERSION,
                    "sensitive-pack",
                    "1.0.0",
                    PackTier.DATA,
                    PackDistribution.OSS,
                    RegulatoryClass.SENSITIVE,
                    PackKind.VERTICAL,
                    List.of(),
                    null,
                    null,
                    List.of(studio("s", StudioPricing.FREE)),
                    Map.of(),
                    null,
                    null,
                    null));

    assertTrue(refused.getMessage().contains("scopeGuard"), refused.getMessage());
  }

  @Test
  @DisplayName("[ADR-051] a SENSITIVE pack that declares its refused domain is accepted")
  void sensitiveWithAScopeGuardIsAccepted() {
    PackBundle bundle =
        new PackBundle(
            PackBundle.API_VERSION,
            "sensitive-pack",
            "1.0.0",
            PackTier.DATA,
            PackDistribution.OSS,
            RegulatoryClass.SENSITIVE,
            PackKind.VERTICAL,
            List.of(),
            null,
            null,
            List.of(studio("s", StudioPricing.FREE)),
            Map.of(),
            new PackScopeGuard(
                List.of("conseil juridique"), "Je ne donne pas de conseil juridique."),
            null,
            null);

    assertTrue(bundle.isSensitive());
  }

  // ── ADR-061: kind, the third classification, and the one combination it cannot take ──────────

  private static PackBundle toolkit(
      RegulatoryClass regulatoryClass,
      PackCatalogEntry catalog,
      PackScopeGuard scopeGuard,
      PackConsent consent,
      PackSafety safety) {
    return new PackBundle(
        PackBundle.API_VERSION,
        "media-toolkit",
        "1.0.0",
        PackTier.CAPABILITY,
        PackDistribution.OSS,
        regulatoryClass,
        PackKind.TOOLKIT,
        List.of(),
        catalog,
        catalog == null ? null : new PackPricing(0, 0, true),
        List.of(studio("image-generation", StudioPricing.INCLUDED)),
        Map.of(),
        scopeGuard,
        consent,
        safety);
  }

  private static PackCatalogEntry shelf() {
    return new PackCatalogEntry("business", "image", PackStatus.PUBLISHED, 0, null);
  }

  private static PackScopeGuard guard() {
    return new PackScopeGuard(List.of("diagnostic"), "Je ne pose pas de diagnostic.");
  }

  @Test
  @DisplayName("[ADR-061] kind defaults to VERTICAL — the kind that grants nothing by itself")
  void kindDefaultsToVertical() {
    PackBundle bundle =
        new PackBundle(
            PackBundle.API_VERSION,
            "legacy-pack",
            "1.0.0",
            null,
            null,
            null,
            null,
            List.of(),
            null,
            null,
            List.of(studio("s", StudioPricing.FREE)),
            Map.of(),
            null,
            null,
            null);

    assertEquals(PackKind.VERTICAL, bundle.kind());
  }

  @Test
  @DisplayName("[ADR-061] a TOOLKIT may be SENSITIVE — kind and class are orthogonal")
  void sensitiveToolkitIsAccepted() {
    PackBundle bundle = toolkit(RegulatoryClass.SENSITIVE, shelf(), guard(), null, null);

    assertEquals(PackKind.TOOLKIT, bundle.kind());
    assertTrue(bundle.isSensitive(), "a TOOLKIT loses none of SENSITIVE's four run controls");
  }

  @Test
  @DisplayName(
      "[ADR-061] a REGULATED TOOLKIT cannot be constructed — consent has no row to live on")
  void regulatedToolkitIsRefused() {
    PackConsent consent = new PackConsent("1.0", "J'accepte.");
    IllegalArgumentException refused =
        assertThrows(
            IllegalArgumentException.class,
            () -> toolkit(RegulatoryClass.REGULATED, shelf(), guard(), consent, null));

    assertTrue(refused.getMessage().contains("REGULATED"), refused.getMessage());
  }

  @Test
  @DisplayName("[ADR-061] a TOOLKIT with no catalog entry is refused — its kind would have no row")
  void uncataloguedToolkitIsRefused() {
    IllegalArgumentException refused =
        assertThrows(
            IllegalArgumentException.class,
            () -> toolkit(RegulatoryClass.STANDARD, null, null, null, null));

    assertTrue(refused.getMessage().contains("catalog"), refused.getMessage());
  }

  @Test
  @DisplayName(
      "[ADR-061] a SENSITIVE TOOLKIT declaring consent is refused, not silently unrecorded")
  void toolkitDeclaringConsentIsRefused() {
    PackConsent consent = new PackConsent("1.0", "J'accepte.");
    IllegalArgumentException refused =
        assertThrows(
            IllegalArgumentException.class,
            () -> toolkit(RegulatoryClass.SENSITIVE, shelf(), guard(), consent, null));

    assertTrue(refused.getMessage().contains("consent or safety"), refused.getMessage());
  }
}
