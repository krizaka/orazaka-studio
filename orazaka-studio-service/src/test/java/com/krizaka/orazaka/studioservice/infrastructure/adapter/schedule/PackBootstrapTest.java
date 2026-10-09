package com.krizaka.orazaka.studioservice.infrastructure.adapter.schedule;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.krizaka.orazaka.studio.domain.model.BlueprintStatus;
import com.krizaka.orazaka.studio.domain.model.PackBlueprint;
import com.krizaka.orazaka.studio.domain.model.PackBundle;
import com.krizaka.orazaka.studio.domain.model.PackDistribution;
import com.krizaka.orazaka.studio.domain.model.PackKind;
import com.krizaka.orazaka.studio.domain.model.PackStudio;
import com.krizaka.orazaka.studio.domain.model.PackTier;
import com.krizaka.orazaka.studio.domain.model.RegulatoryClass;
import com.krizaka.orazaka.studio.domain.model.StudioPricing;
import com.krizaka.orazaka.studio.domain.model.StudioStatus;
import com.krizaka.orazaka.studioservice.application.service.PackInstallerService;
import com.krizaka.orazaka.studioservice.domain.exception.PackInstallException;
import com.krizaka.orazaka.studioservice.domain.port.PackInstallRepository;
import com.krizaka.orazaka.studioservice.infrastructure.support.PackBundleResolver;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What bootstrap does when a start is not the happy one (ADR-068).
 *
 * <p>The installed case is asserted against a real database by {@code PackBootstrapIT}. What is
 * asserted here is the behaviour that only shows up when something is wrong, and that decides
 * whether a fresh environment ever becomes whole: a dependency that is still starting must be
 * retried, a manifest that cannot be read must not be, and neither may stop the bundles that are
 * fine.
 */
class PackBootstrapTest {

  private static final Path FIRST = Path.of("orazaka-packs", "first");
  private static final Path SECOND = Path.of("orazaka-packs", "second");

  private static PackBundle bundle(String key) {
    PackBlueprint blueprint =
        new PackBlueprint(
            "1.0.0", BlueprintStatus.PUBLISHED, "{\"steps\":[]}", "{}", "{}", 0, null, "orazaka");
    return new PackBundle(
        PackBundle.API_VERSION,
        key,
        "1.0.0",
        PackTier.DATA,
        PackDistribution.OSS,
        RegulatoryClass.STANDARD,
        PackKind.VERTICAL,
        List.of(),
        null,
        null,
        List.of(
            new PackStudio(
                key + "-studio",
                "general",
                "studio",
                StudioPricing.FREE,
                "studio." + key + "-studio",
                StudioStatus.PUBLISHED,
                "orazaka",
                0,
                blueprint)),
        Map.of(),
        null,
        null,
        null);
  }

  @Test
  @DisplayName(
      "a bundle whose install fails is retried at the next tick, and the pass is not settled")
  void aFailedInstallIsRetried() {
    PackBundleResolver resolver = mock(PackBundleResolver.class);
    PackInstallerService installer = mock(PackInstallerService.class);
    PackInstallRepository repository = mock(PackInstallRepository.class);
    when(resolver.discover()).thenReturn(List.of(FIRST));
    when(resolver.read(FIRST)).thenReturn(bundle("first"));
    when(repository.isApplied(any())).thenReturn(false);
    // What a job service that has not finished starting looks like from here.
    when(installer.install(any()))
        .thenThrow(new PackInstallException("capability does not resolve"));

    PackBootstrap bootstrap = new PackBootstrap(resolver, installer, repository);
    bootstrap.install();
    bootstrap.install();

    verify(installer, times(2))
        .install(
            org.mockito.ArgumentMatchers.argThat(candidate -> "first".equals(candidate.key())));
  }

  @Test
  @DisplayName("a bundle that cannot be read is skipped, and the bundles beside it still install")
  void anUnreadableBundleDoesNotStopTheRest() {
    PackBundleResolver resolver = mock(PackBundleResolver.class);
    PackInstallerService installer = mock(PackInstallerService.class);
    PackInstallRepository repository = mock(PackInstallRepository.class);
    when(resolver.discover()).thenReturn(List.of(FIRST, SECOND));
    when(resolver.read(FIRST)).thenThrow(new PackInstallException("pack.yaml is not a mapping"));
    when(resolver.read(SECOND)).thenReturn(bundle("second"));
    when(repository.isApplied(any())).thenReturn(false);

    PackBootstrap bootstrap = new PackBootstrap(resolver, installer, repository);
    bootstrap.install();

    verify(installer, times(1)).install(any());
    // A manifest fault is not transient: the second pass does nothing, because the first settled.
    bootstrap.install();
    verify(resolver, times(1)).discover();
  }

  @Test
  @DisplayName("a bundle the catalogue already carries is left alone")
  void anInstalledBundleIsNotReapplied() {
    PackBundleResolver resolver = mock(PackBundleResolver.class);
    PackInstallerService installer = mock(PackInstallerService.class);
    PackInstallRepository repository = mock(PackInstallRepository.class);
    when(resolver.discover()).thenReturn(List.of(FIRST));
    when(resolver.read(FIRST)).thenReturn(bundle("first"));
    when(repository.isApplied(any())).thenReturn(true);

    new PackBootstrap(resolver, installer, repository).install();

    verify(installer, never()).install(any());
  }

  @Test
  @DisplayName("a deployment that declares no pack source bootstraps nothing")
  void noSourceMeansNoBootstrap() {
    PackBundleResolver resolver = mock(PackBundleResolver.class);
    PackInstallerService installer = mock(PackInstallerService.class);
    PackInstallRepository repository = mock(PackInstallRepository.class);
    when(resolver.discover()).thenReturn(List.of());

    // An empty source list is a deployment's choice, not a fault: it is looked up, it answers
    // nothing, and the service starts.
    new PackBootstrap(resolver, installer, repository).install();

    verify(resolver).discover();
    verify(installer, never()).install(any());
    verify(repository, never()).isApplied(any());
  }
}
