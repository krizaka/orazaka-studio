package com.orazaka.studioservice.application.service;

import com.orazaka.billing.domain.model.EntitlementGrant;
import com.orazaka.billing.domain.model.PackProvision;
import com.orazaka.billing.domain.port.PackProvisioningClient;
import com.orazaka.jobs.domain.model.CapabilityDeclaration;
import com.orazaka.jobs.domain.port.CapabilityRegistrationClient;
import com.orazaka.jobs.domain.port.CapabilityRoutingClient;
import com.orazaka.studio.domain.model.PackBundle;
import com.orazaka.studio.domain.model.PackCapability;
import com.orazaka.studio.domain.model.PackStudio;
import com.orazaka.studio.domain.model.RegulatoryClass;
import com.orazaka.studioservice.domain.exception.PackInstallException;
import com.orazaka.studioservice.domain.port.PackInstallRepository;
import com.orazaka.studioservice.infrastructure.config.AssetStoreProperties;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Installs a pack bundle: capabilities, billing, catalogue — in that order, or not at all.
 *
 * <p>Seam S4 of ADR-037. A bundle is applied as one operation from the caller's point of view, and
 * it is worth being exact about what that can and cannot mean here, because the honest answer is
 * not "one transaction".
 *
 * <p><b>Why it is a compensated sequence and not a distributed transaction.</b> An install writes
 * three databases owned by three services: {@code orazaka_db} holds {@code orazaka_capabilities},
 * {@code orazaka_billing_db} holds the price and the grants, {@code orazaka_studio_db} holds the
 * catalogue. SEAM-001 forbids the cross-context foreign keys that would be needed to make them one
 * schema, and deliberately so — that separation is what lets the catalogue be edited without
 * deploying the service that holds the credit ledger. A two-phase commit across three services
 * would trade that property away to buy atomicity for an admin-speed operation that happens a
 * handful of times per release. So each context applies its own slice in its own local transaction,
 * this service sequences them, and a failure compensates backwards.
 *
 * <p><b>The order is the design, not an implementation detail.</b> Capabilities first: a blueprint
 * naming a capability that does not resolve is a Studio that fails at dispatch, and it must be
 * impossible to publish one. Billing second: a {@code pack_studio} row whose matching {@code
 * billing_pack_entitlement} is missing locks out exactly the customer who just paid (ADR-036,
 * invariant #3), so the grant exists before the row that promises it. Catalogue last, in one local
 * transaction, because it is the slice a user can see — and the one slice that must never be
 * half-written.
 *
 * <p><b>Compensation is best-effort and says so.</b> Undoing the earlier slices runs while a
 * failure is already being handled and can itself fail. What it must never do is replace the
 * original error, so each step is logged and the install's own exception is what reaches the
 * caller.
 */
@Service
public class PackInstallerService {

  private static final Logger logger = LoggerFactory.getLogger(PackInstallerService.class);

  private final PackInstallRepository packInstallRepository;
  private final CapabilityRoutingClient capabilityRoutingClient;
  private final CapabilityRegistrationClient capabilityRegistrationClient;
  private final PackProvisioningClient packProvisioningClient;
  private final AssetStoreProperties assetStore;

  public PackInstallerService(
      PackInstallRepository packInstallRepository,
      CapabilityRoutingClient capabilityRoutingClient,
      CapabilityRegistrationClient capabilityRegistrationClient,
      PackProvisioningClient packProvisioningClient,
      AssetStoreProperties assetStore) {
    this.packInstallRepository =
        Objects.requireNonNull(packInstallRepository, "PackInstallRepository cannot be null");
    this.capabilityRoutingClient =
        Objects.requireNonNull(capabilityRoutingClient, "CapabilityRoutingClient cannot be null");
    this.capabilityRegistrationClient =
        Objects.requireNonNull(
            capabilityRegistrationClient, "CapabilityRegistrationClient cannot be null");
    this.packProvisioningClient =
        Objects.requireNonNull(packProvisioningClient, "PackProvisioningClient cannot be null");
    this.assetStore = Objects.requireNonNull(assetStore, "AssetStoreProperties cannot be null");
  }

  /**
   * Checks a bundle without writing anything.
   *
   * <p>The semantic half of validation. A manifest's SHAPE is checked against {@code
   * pack.schema.json} before it ever reaches this service; what cannot be checked from the file
   * alone is whether the platform it is being installed onto can actually run it — which
   * capabilities resolve, and whether the bundle's own declarations are self-consistent. That is
   * this method, and it is why {@code orazaka pack validate} against a running platform tells you
   * more than the same command offline.
   *
   * @param bundle the resolved manifest
   * @return every problem found, in the order found; empty when the bundle is installable
   */
  public List<String> validate(PackBundle bundle) {
    Objects.requireNonNull(bundle, "bundle must not be null");
    List<String> problems = new ArrayList<>();

    // The fifth control of SENSITIVE (ADR-051 §8). The other four govern the run — what is
    // recorded, kept how long, read back by whom, answered how. None of them encrypts the bytes,
    // and a protected pack's documents are exactly the bytes worth encrypting. The platform
    // declares whether it does; it defaults to "no", so a deployment that has not answered is
    // treated as one that stores them in the clear.
    if (bundle.regulatoryClass() != RegulatoryClass.STANDARD
        && !assetStore.mayHoldProtectedDocuments()) {
      problems.add(
          "pack "
              + bundle.key()
              + " declares regulatoryClass "
              + bundle.regulatoryClass()
              + " and this deployment stores assets in the clear: set"
              + " orazaka.studio-service.assets.encrypted-at-rest=true once the store encrypts"
              + " them, or orazaka.studio-service.assets.deployment=LOCAL if this is a developer"
              + " machine whose disk is the data subject's own (ADR-051 §8)");
    }

    Set<String> contributed =
        bundle.capabilities().stream()
            .map(PackCapability::key)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

    for (PackStudio studio : bundle.studios()) {
      for (String featureKey : capabilityKeysOf(studio)) {
        // A capability the bundle itself contributes counts as resolvable: it will exist by the
        // time any run dispatches, because capabilities are written before the catalogue.
        if (contributed.contains(featureKey)) {
          continue;
        }
        if (capabilityRoutingClient.route(featureKey).isEmpty()) {
          problems.add(
              "studio "
                  + studio.key()
                  + " step capability "
                  + featureKey
                  + " resolves to no enabled route, and the bundle does not contribute it");
        }
      }
    }
    return problems;
  }

  /**
   * Applies a bundle.
   *
   * @param bundle the resolved manifest
   * @return how many Studios were installed
   * @throws PackInstallException when any slice failed; earlier slices are compensated first
   */
  public int install(PackBundle bundle) {
    Objects.requireNonNull(bundle, "bundle must not be null");
    List<String> problems = validate(bundle);
    if (!problems.isEmpty()) {
      throw new PackInstallException(
          "Bundle " + bundle.key() + " is not installable: " + String.join("; ", problems));
    }

    List<String> registeredCapabilities = new ArrayList<>();
    boolean provisioned = false;
    try {
      for (PackCapability capability : bundle.capabilities()) {
        capabilityRegistrationClient.register(toDeclaration(capability));
        registeredCapabilities.add(capability.key());
      }
      if (bundle.isCatalogued()) {
        packProvisioningClient.provision(toProvision(bundle));
        provisioned = true;
      }
      int installed = packInstallRepository.apply(bundle);
      logger.info("Installed pack bundle {} v{}", bundle.key(), bundle.version());
      return installed;
    } catch (RuntimeException failure) {
      compensate(bundle, registeredCapabilities, provisioned);
      throw new PackInstallException(
          "Install of bundle " + bundle.key() + " failed and was rolled back", failure);
    }
  }

  /**
   * Removes a bundle's catalogue slice and withdraws its pack from sale.
   *
   * <p>Capabilities are deliberately left in place. A capability may be named by a blueprint from
   * another bundle, and removing one because the pack that introduced it was uninstalled would
   * break a Studio that has nothing to do with this operation. The install path removes them only
   * when compensating its OWN failed attempt, where it knows it wrote them moments ago and nothing
   * has had time to depend on them.
   *
   * @param bundleKey the bundle to remove
   * @return {@code true} when anything was removed
   */
  public boolean uninstall(String bundleKey) {
    boolean removed = packInstallRepository.remove(bundleKey);
    if (removed) {
      packProvisioningClient.withdraw(bundleKey);
    }
    return removed;
  }

  private void compensate(
      PackBundle bundle, List<String> registeredCapabilities, boolean provisioned) {
    // Backwards through what succeeded. Each step is guarded: compensation runs while another
    // failure is being handled, and an exception here would replace the error the caller needs.
    try {
      packInstallRepository.remove(bundle.key());
    } catch (RuntimeException e) {
      logger.error("Could not remove catalogue rows for {} while compensating", bundle.key(), e);
    }
    if (provisioned) {
      packProvisioningClient.withdraw(bundle.key());
    }
    for (String featureKey : registeredCapabilities) {
      try {
        capabilityRegistrationClient.unregister(featureKey);
      } catch (RuntimeException e) {
        logger.error("Could not unregister capability {} while compensating", featureKey, e);
      }
    }
  }

  /**
   * The capability keys a Studio's blueprint dispatches to.
   *
   * <p>Read from the blueprint's raw definition rather than a parsed graph: the installer must
   * check a blueprint BEFORE it is stored, and {@code BlueprintRepository} parses on read from the
   * table. Matching the {@code "featureKey": "..."} pairs is enough for the question being asked —
   * which capabilities does this graph name — and does not require this service to own a second
   * copy of the blueprint grammar.
   */
  private static Set<String> capabilityKeysOf(PackStudio studio) {
    Set<String> keys = new LinkedHashSet<>();
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("\"featureKey\"\\s*:\\s*\"([^\"]+)\"")
            .matcher(studio.blueprint().definition());
    while (matcher.find()) {
      keys.add(matcher.group(1));
    }
    return keys;
  }

  private static CapabilityDeclaration toDeclaration(PackCapability capability) {
    return new CapabilityDeclaration(
        capability.key(),
        capability.handlerKey(),
        capability.routingKey(),
        capability.billableUnit(),
        capability.billableCapability(),
        "BATCH",
        capability.inputSchema(),
        capability.outputSchema(),
        capability.enabled());
  }

  /**
   * The billing slice: the price, and one grant per Studio the pack bundles.
   *
   * <p>The grants are DERIVED from the Studios rather than declared separately in the manifest. A
   * manifest that stated both would let them disagree, and the disagreement has exactly one
   * symptom: a customer who bought the pack and cannot open the Studio it advertises.
   */
  private static PackProvision toProvision(PackBundle bundle) {
    List<EntitlementGrant> grants =
        bundle.studios().stream()
            .map(studio -> EntitlementGrant.unlocking(studio.entitlementKey()))
            .toList();
    return new PackProvision(
        bundle.key(),
        bundle.pricing().priceCents(),
        bundle.pricing().includedCredits(),
        bundle.pricing().isActive(),
        grants);
  }
}
