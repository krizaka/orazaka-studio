package com.orazaka.studioservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.krizaka.billing.domain.port.PackProvisioningClient;
import com.orazaka.jobs.domain.model.CapabilityRoute;
import com.orazaka.jobs.domain.port.CapabilityRegistrationClient;
import com.orazaka.jobs.domain.port.CapabilityRoutingClient;
import com.orazaka.studio.domain.model.BlueprintStatus;
import com.orazaka.studio.domain.model.PackBlueprint;
import com.orazaka.studio.domain.model.PackBundle;
import com.orazaka.studio.domain.model.PackCatalogEntry;
import com.orazaka.studio.domain.model.PackDistribution;
import com.orazaka.studio.domain.model.PackKind;
import com.orazaka.studio.domain.model.PackPricing;
import com.orazaka.studio.domain.model.PackScopeGuard;
import com.orazaka.studio.domain.model.PackStatus;
import com.orazaka.studio.domain.model.PackStudio;
import com.orazaka.studio.domain.model.PackTier;
import com.orazaka.studio.domain.model.RegulatoryClass;
import com.orazaka.studio.domain.model.StudioPricing;
import com.orazaka.studio.domain.model.StudioStatus;
import com.orazaka.studioservice.domain.port.PackInstallRepository;
import com.orazaka.studioservice.infrastructure.config.AssetStoreProperties;
import com.orazaka.studioservice.infrastructure.config.AssetStoreProperties.Deployment;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The fifth control of SENSITIVE: a deployment that stores documents in the clear does not get to
 * hold a protected pack's (ADR-051 §8).
 */
class PackInstallerServiceTest {

  private static final PackScopeGuard GUARD =
      new PackScopeGuard(List.of("conseil juridique"), "Je ne donne pas de conseil juridique.");

  private static PackBundle bundle(RegulatoryClass regulatoryClass, PackScopeGuard guard) {
    PackBlueprint blueprint =
        new PackBlueprint(
            "1.0.0", BlueprintStatus.PUBLISHED, "{\"steps\":[]}", "{}", "{}", 100, "x", "system");
    PackStudio studio =
        new PackStudio(
            "a-studio",
            "legal",
            "studio",
            StudioPricing.FREE,
            "studio.a-studio",
            StudioStatus.PUBLISHED,
            "orazaka",
            10,
            blueprint);
    return new PackBundle(
        PackBundle.API_VERSION,
        "a-pack",
        "1.0.0",
        PackTier.DATA,
        PackDistribution.OSS,
        regulatoryClass,
        PackKind.VERTICAL,
        List.of(),
        new PackCatalogEntry("business", "sparkles", PackStatus.PUBLISHED, 10, null),
        new PackPricing(0, 0L, true),
        List.of(studio),
        Map.of(),
        guard,
        null,
        null);
  }

  private static PackInstallerService installerOn(AssetStoreProperties assets) {
    CapabilityRegistrationClient registration = mock(CapabilityRegistrationClient.class);
    CapabilityRoutingClient routing = mock(CapabilityRoutingClient.class);
    when(routing.route(anyString()))
        .thenReturn(
            Optional.of(
                new CapabilityRoute("k", "job.chat.generate", "CHAT_STEP", "CHAT", "BATCH", true)));
    return new PackInstallerService(
        mock(PackInstallRepository.class),
        routing,
        registration,
        mock(PackProvisioningClient.class),
        assets);
  }

  @Test
  @DisplayName("[ADR-051] a SENSITIVE pack is refused where assets are stored in the clear")
  void sensitivePackIsRefusedOnAnUnencryptedHostedDeployment() {
    PackInstallerService installer =
        installerOn(new AssetStoreProperties(false, Deployment.HOSTED));

    List<String> problems = installer.validate(bundle(RegulatoryClass.SENSITIVE, GUARD));

    assertThat(problems)
        .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
        .contains("a-pack")
        .contains("SENSITIVE")
        .contains("orazaka.studio-service.assets.encrypted-at-rest");
  }

  @Test
  @DisplayName("[ADR-051] the same pack installs on a developer's own machine")
  void sensitivePackIsAllowedLocally() {
    PackInstallerService installer = installerOn(new AssetStoreProperties(false, Deployment.LOCAL));

    assertThat(installer.validate(bundle(RegulatoryClass.SENSITIVE, GUARD))).isEmpty();
  }

  @Test
  @DisplayName("[ADR-051] and on a hosted deployment once option B has landed")
  void sensitivePackIsAllowedWhereTheStoreEncrypts() {
    PackInstallerService installer = installerOn(new AssetStoreProperties(true, Deployment.HOSTED));

    assertThat(installer.validate(bundle(RegulatoryClass.SENSITIVE, GUARD))).isEmpty();
  }

  @Test
  @DisplayName("a STANDARD pack is unaffected: the control belongs to the regulatory class")
  void standardPackIsUnaffected() {
    PackInstallerService installer =
        installerOn(new AssetStoreProperties(false, Deployment.HOSTED));

    assertThat(installer.validate(bundle(RegulatoryClass.STANDARD, null))).isEmpty();
  }
}
