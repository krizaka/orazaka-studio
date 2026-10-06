package com.orazaka.studioservice.application.service;

import com.orazaka.billing.domain.model.PackPrice;
import com.orazaka.billing.domain.port.PackPricingClient;
import com.orazaka.studio.domain.model.Pack;
import com.orazaka.studio.domain.model.PackCategory;
import com.orazaka.studio.domain.model.PackSummary;
import com.orazaka.studio.domain.model.Studio;
import com.orazaka.studio.domain.port.PackCatalogClient;
import com.orazaka.studioservice.domain.model.PackAccess;
import com.orazaka.studioservice.domain.model.StudioAccess;
import com.orazaka.studioservice.domain.port.PackRepository;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * The Pack catalogue — what a user buys, on which shelf, and what it bundles (ADR-036).
 *
 * <p>The catalogue owns a Pack's <b>identity and content</b>. Its <b>price</b> is billing's answer
 * and is fetched across the context boundary at render time rather than copied into this database,
 * because a duplicated price goes stale and a stale price is a billing dispute rather than a
 * rendering bug.
 *
 * <p>Reads only, and the canonical implementation of the Tier-1 {@link PackCatalogClient}: the
 * contract a consumer composes against is satisfied here, in the service that owns the tables,
 * rather than by a second in-process shape that could drift from it.
 *
 * <p><b>One billing call for the whole page.</b> Browsing forty Packs asks billing once, not forty
 * times — the same arithmetic {@link StudioAccessService#evaluateAll} states for entitlements. And
 * when billing does not answer, the catalogue is returned with prices <b>absent</b>: a marketing
 * page that returns 500 because the credit ledger is restarting is a self-inflicted outage, and an
 * absent price renders as "—" rather than as "free".
 */
@Service
public class PackCatalogService implements PackCatalogClient {

  private final PackRepository packRepository;
  private final PackPricingClient packPricingClient;
  private final StudioCatalogService studioCatalogService;
  private final StudioAccessService studioAccessService;

  public PackCatalogService(
      PackRepository packRepository,
      PackPricingClient packPricingClient,
      StudioCatalogService studioCatalogService,
      StudioAccessService studioAccessService) {
    this.packRepository = Objects.requireNonNull(packRepository, "PackRepository required");
    this.packPricingClient =
        Objects.requireNonNull(packPricingClient, "PackPricingClient required");
    this.studioCatalogService =
        Objects.requireNonNull(studioCatalogService, "StudioCatalogService required");
    this.studioAccessService =
        Objects.requireNonNull(studioAccessService, "StudioAccessService required");
  }

  /**
   * The marketplace band for each card: how this actor gets this Pack (ADR-066).
   *
   * <p>Keyed on {@code kind} <b>and</b> the entitlement snapshot, and resolved here because the
   * client cannot: reading {@code kind == TOOLKIT} alone told an unentitled actor a pack was
   * included and then offered them no way to get it.
   *
   * <p>One snapshot for the whole page, the arithmetic {@link StudioAccessService#evaluateAll}
   * exists for: a pack is entitled when every Studio it bundles is open to this actor, because a
   * bundle half of which is locked is not something anybody has.
   *
   * @param cards the catalogue page
   * @param actorId the opaque billable subject
   * @return the band per pack key
   */
  public Map<String, PackAccess> bands(List<PackSummary> cards, String actorId) {
    if (cards.isEmpty()) {
      return Map.of();
    }
    List<Studio> studios = studioCatalogService.browse(null, null);
    Map<String, StudioAccess> access = studioAccessService.evaluateAll(studios, actorId);
    Map<String, PackAccess> bands = new LinkedHashMap<>();
    for (PackSummary card : cards) {
      bands.put(
          card.pack().packKey(),
          PackAccess.of(card.pack().kind(), entitled(card, access), card.priceCents()));
    }
    return Map.copyOf(bands);
  }

  /**
   * Whether every Studio this pack bundles is open to the actor.
   *
   * <p>A pack that bundles nothing is not "had": there is nothing it could have granted.
   */
  private static boolean entitled(PackSummary card, Map<String, StudioAccess> access) {
    List<String> studioKeys = card.pack().studioKeys();
    if (studioKeys.isEmpty()) {
      return false;
    }
    return studioKeys.stream()
        .allMatch(key -> access.containsKey(key) && !access.get(key).locked());
  }

  /**
   * The published catalogue, priced, optionally narrowed to one shelf.
   *
   * @param categoryKey the shelf to browse, or {@code null} for every shelf
   * @param locale the caller's locale; a Pack without that translation falls back
   * @return the cards, heaviest sort weight first; Packs whose shelf no longer resolves are
   *     omitted, because a card with no heading has nowhere to render
   */
  @Override
  public List<PackSummary> browse(String categoryKey, String locale) {
    List<Pack> packs = packRepository.browse(categoryKey, locale);
    if (packs.isEmpty()) {
      return List.of();
    }
    Map<String, PackCategory> shelves = shelvesByKey(locale);
    Map<String, PackPrice> prices = packPricingClient.prices(keysOf(packs));

    return packs.stream()
        .filter(pack -> shelves.containsKey(pack.categoryKey()))
        .map(pack -> summarise(pack, shelves.get(pack.categoryKey()), prices.get(pack.packKey())))
        .toList();
  }

  /**
   * One Pack by key, whatever its publication status.
   *
   * @param packKey the Pack's key
   * @param locale the caller's locale
   * @return the Pack, or empty when no such key exists
   */
  @Override
  public Optional<Pack> find(String packKey, String locale) {
    return packRepository.find(packKey, locale);
  }

  /**
   * The active shelves, for the marketplace's own headings.
   *
   * @param locale the caller's locale
   * @return the categories, heaviest sort weight first
   */
  @Override
  public List<PackCategory> categories(String locale) {
    return packRepository.categories(locale);
  }

  /**
   * One Pack's card, priced.
   *
   * @param packKey the Pack's key
   * @param locale the caller's locale
   * @return the card, or empty when the key names nothing or its shelf no longer resolves
   */
  public Optional<PackSummary> summary(String packKey, String locale) {
    Optional<Pack> found = packRepository.find(packKey, locale);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    Pack pack = found.get();
    PackCategory shelf = shelvesByKey(locale).get(pack.categoryKey());
    if (shelf == null) {
      return Optional.empty();
    }
    return Optional.of(
        summarise(pack, shelf, packPricingClient.prices(Set.of(packKey)).get(packKey)));
  }

  private static PackSummary summarise(Pack pack, PackCategory shelf, PackPrice price) {
    return new PackSummary(
        pack,
        shelf,
        price == null ? null : price.priceCents(),
        price == null ? null : price.includedCredits());
  }

  private Map<String, PackCategory> shelvesByKey(String locale) {
    Map<String, PackCategory> shelves = new LinkedHashMap<>();
    for (PackCategory category : packRepository.categories(locale)) {
      shelves.put(category.categoryKey(), category);
    }
    return shelves;
  }

  private static Set<String> keysOf(List<Pack> packs) {
    Set<String> keys = new LinkedHashSet<>();
    for (Pack pack : packs) {
      keys.add(pack.packKey());
    }
    return keys;
  }
}
