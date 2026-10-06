package com.orazaka.studio.domain.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A pack manifest, resolved: the whole of what a bundle contributes, in one value.
 *
 * <p>This is the install unit of ADR-037 §3.2 and the shape {@code pack.yaml} deserialises to once
 * its {@code i18n/*.yaml} overlays and its blueprint files have been read in. The manifest names
 * files; this record holds their contents, so that by the time the installer sees a bundle there is
 * nothing left to fetch and an install cannot fail halfway for want of a file on disk.
 *
 * <p><b>Optional {@code catalog} is a real case, not laxity.</b> Two of the three Studios shipping
 * today ({@code trade-showcase}, {@code outbound-prospection}) belong to no pack: they are FREE and
 * INCLUDED entries with no {@code pack} row, no price and no entitlement grant to buy. A manifest
 * format that could not express the catalogue as it already exists would have failed its first
 * test, so a bundle without {@code catalog} ships Studios only.
 *
 * @param apiVersion the manifest grammar this bundle was written against
 * @param key the bundle's stable identity; also the {@code pack_key} when catalogued
 * @param version the bundle's semver
 * @param tier what the pack needs from the platform — DATA, CAPABILITY or WORKER
 * @param distribution where the pack may be obtained — OSS, CLOUD or PARTNER
 * @param regulatoryClass the weight it carries, for the phase-I controls
 * @param kind how it reaches the user — VERTICAL or TOOLKIT; orthogonal to tier and class (ADR-061)
 * @param capabilities capabilities the pack CONTRIBUTES; empty for a DATA pack
 * @param catalog the shelf entry, or {@code null} when the bundle ships Studios only
 * @param pricing what it costs and what buying it grants; {@code null} without a catalog entry
 * @param studios the Studios it ships, each with its blueprint already read; never empty
 * @param translations locale → the localised strings for this bundle; defensively copied
 * @param scopeGuard the domain this pack refuses; required above {@code STANDARD} (ADR-051)
 * @param consent the versioned consent required before install; required for {@code REGULATED}
 * @param safety the crisis declaration; required for {@code REGULATED} (ADR-055)
 */
public record PackBundle(
    String apiVersion,
    String key,
    String version,
    PackTier tier,
    PackDistribution distribution,
    RegulatoryClass regulatoryClass,
    PackKind kind,
    List<PackCapability> capabilities,
    PackCatalogEntry catalog,
    PackPricing pricing,
    List<PackStudio> studios,
    Map<String, PackTranslation> translations,
    PackScopeGuard scopeGuard,
    PackConsent consent,
    PackSafety safety) {

  /**
   * The one grammar this platform reads. A bundle claiming another is refused, never guessed at.
   */
  public static final String API_VERSION = "orazaka.dev/v1";

  private static final Pattern KEY = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

  /** Compact canonical constructor enforcing the bundle's invariants (ERR-106). */
  public PackBundle {
    if (!API_VERSION.equals(apiVersion)) {
      throw new IllegalArgumentException(
          "unsupported manifest apiVersion '"
              + apiVersion
              + "'; this platform reads "
              + API_VERSION);
    }
    if (key == null || !KEY.matcher(key).matches()) {
      throw new IllegalArgumentException("pack key must be kebab-case: " + key);
    }
    Objects.requireNonNull(version, "version must not be null");
    if (studios == null || studios.isEmpty()) {
      throw new IllegalArgumentException("bundle " + key + " ships no Studio");
    }
    // A catalogued pack without a price is unsellable, and a PAID Studio without a pack has
    // nothing to open a checkout for — the same invariant studio_pricing_needs_package spells
    // in SQL, enforced here so it cannot be violated between the manifest and the table.
    if (catalog != null && pricing == null) {
      throw new IllegalArgumentException("catalogued pack " + key + " declares no pricing");
    }
    boolean sellsAPaidStudio =
        studios.stream().anyMatch(studio -> studio.pricing() == StudioPricing.PAID);
    if (sellsAPaidStudio && catalog == null) {
      throw new IllegalArgumentException(
          "bundle " + key + " ships a PAID Studio but declares no catalog entry to sell it in");
    }
    tier = tier == null ? PackTier.DATA : tier;
    distribution = distribution == null ? PackDistribution.OSS : distribution;
    regulatoryClass = regulatoryClass == null ? RegulatoryClass.STANDARD : regulatoryClass;
    kind = kind == null ? PackKind.VERTICAL : kind;
    // A TOOLKIT's installation is DERIVED from entitlement and never stored (ADR-061). Three
    // declarations need a stored installation or a stored pack row, so a TOOLKIT cannot make them.
    // Refused here, at the boundary the manifest crosses, and again by ck_pack_toolkit_* in SQL.
    if (kind == PackKind.TOOLKIT) {
      // Its kind is a column on the pack row, and a bundle with no catalog entry writes no row: an
      // uncatalogued TOOLKIT would install as a VERTICAL with nothing to say so.
      if (catalog == null) {
        throw new IllegalArgumentException(
            "pack "
                + key
                + " declares kind TOOLKIT and no catalog entry; its kind lives on the"
                + " pack row, and a bundle without a catalog entry writes none");
      }
      // Consent is recorded on studio_installation, which a TOOLKIT does not have. A consent gate
      // with nowhere to record what was agreed to is not a consent gate.
      if (regulatoryClass == RegulatoryClass.REGULATED) {
        throw new IllegalArgumentException(
            "pack "
                + key
                + " declares kind TOOLKIT and regulatoryClass REGULATED; consent is"
                + " recorded on an installation, and a TOOLKIT's installation is derived");
      }
      // Below REGULATED these are optional, and still need a row: consent for the same reason, and
      // safety because its reply is chosen by the installation's region. Without one the crisis
      // reply resolves to nothing, silently (StepDeclarationService.crisisResponse).
      if (consent != null || safety != null) {
        throw new IllegalArgumentException(
            "pack "
                + key
                + " declares kind TOOLKIT with a consent or safety declaration; both"
                + " are answered from an installation row, and a TOOLKIT has none");
      }
    }
    // THE PUBLISH GATE, in code rather than in a checklist (PACK_CATALOGUE §6.2). A SENSITIVE pack
    // that declares no scope guard cannot be constructed, so it cannot be validated, installed or
    // published — the refusal happens at the boundary the manifest crosses rather than at some
    // later step somebody could add a bypass to. The other three controls need no gate because
    // nothing can switch them off: they follow from the class the run is stamped with.
    if (regulatoryClass != RegulatoryClass.STANDARD && scopeGuard == null) {
      throw new IllegalArgumentException(
          "pack "
              + key
              + " declares regulatoryClass "
              + regulatoryClass
              + " and no scopeGuard; a SENSITIVE pack must state the domain it refuses (ADR-051)");
    }
    // REGULATED adds two controls to SENSITIVE's four, and both are declarations only the pack can
    // make: what the user is consenting to, and what this pack answers someone in crisis. A pack
    // that declares the class without them would get the label and none of the protection, which
    // is the failure mode a regulatory class exists to prevent (ADR-055).
    if (regulatoryClass == RegulatoryClass.REGULATED) {
      if (consent == null) {
        throw new IllegalArgumentException(
            "pack "
                + key
                + " declares regulatoryClass REGULATED and no consent; health data is a"
                + " special category and install must be blocked until consent is recorded"
                + " (GDPR Art. 9, Loi 25)");
      }
      if (safety == null) {
        throw new IllegalArgumentException(
            "pack "
                + key
                + " declares regulatoryClass REGULATED and no safety declaration; a pack"
                + " in this class must state what it treats as crisis content, what it answers,"
                + " and which verified resource it points to");
      }
    }
    capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    studios = List.copyOf(studios);
    translations = translations == null ? Map.of() : Map.copyOf(translations);
  }

  /**
   * @return whether this pack's runs carry a data class, a shorter retention and an audit trail
   */
  /** Whether this pack is in the class that adds consent and crisis handling. */
  public boolean isRegulated() {
    return regulatoryClass == RegulatoryClass.REGULATED;
  }

  public boolean isSensitive() {
    return regulatoryClass != RegulatoryClass.STANDARD;
  }

  /**
   * Whether this bundle contributes a shelf entry.
   *
   * @return {@code true} when a {@code pack} row and its billing half must be written
   */
  public boolean isCatalogued() {
    return catalog != null;
  }
}
