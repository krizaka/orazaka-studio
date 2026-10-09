package com.krizaka.orazaka.studioservice.infrastructure.adapter.schedule;

import com.krizaka.orazaka.studio.domain.model.PackBundle;
import com.krizaka.orazaka.studioservice.application.service.PackInstallerService;
import com.krizaka.orazaka.studioservice.domain.port.PackInstallRepository;
import com.krizaka.orazaka.studioservice.infrastructure.support.PackBundleResolver;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Installs the packs this deployment ships, so a fresh environment has Studios (ADR-068, {@code
 * #45}).
 *
 * <p><b>The defect this closes is one layer up from a missing command.</b> A shipped pack had no
 * bootstrap path at all: a fresh clone got the bundle files and an empty catalogue, and the only
 * way from one to the other was an operator remembering to type {@code orazaka pack install --all}.
 * That is the same shape as the seed files before M2 — content the platform ships, applied by
 * nobody — and it becomes load-bearing the moment door 1 closes, because from then on the media
 * capabilities are reachable <i>only</i> through the Studios this installs.
 *
 * <p><b>In process, not over its own HTTP surface</b>, for the reason the job service's worker
 * self-registration gives: calling itself through the loopback to write its own catalogue would be
 * ceremony, and it would make startup depend on the web layer and on a token this service would
 * have to mint for itself. {@code POST /studios/packs/bundles} stays exactly what it was — how an
 * <i>operator</i> installs a bundle that is not shipped here.
 *
 * <p><b>Two failure modes, deliberately treated differently.</b> An install that fails because the
 * job service or billing is not up yet is transient, and the next tick retries it. A bundle whose
 * manifest cannot be read is not: retrying a syntax error every thirty seconds produces a log
 * nobody reads, so it is reported once and skipped, and the bundle stays uninstalled until someone
 * fixes the file. Neither stops the service — a studio service that refused to start because a pack
 * directory was malformed would take the whole catalogue down for one bad bundle.
 *
 * <p><b>It stops.</b> Once a pass finds nothing missing the schedule is done and the work is not
 * repeated. This is a bootstrap, not a file watcher: a bundle added to a running platform is an
 * operator's install, and re-reading seven directories forever to notice one would be a poll
 * dressed as a guarantee.
 */
@Component
public class PackBootstrap {

  private static final Logger logger = LoggerFactory.getLogger(PackBootstrap.class);

  private final PackBundleResolver packBundleResolver;
  private final PackInstallerService packInstallerService;
  private final PackInstallRepository packInstallRepository;

  /** Whether a pass has completed with nothing left to install. */
  private final AtomicBoolean settled = new AtomicBoolean(false);

  public PackBootstrap(
      PackBundleResolver packBundleResolver,
      PackInstallerService packInstallerService,
      PackInstallRepository packInstallRepository) {
    this.packBundleResolver =
        Objects.requireNonNull(packBundleResolver, "PackBundleResolver cannot be null");
    this.packInstallerService =
        Objects.requireNonNull(packInstallerService, "PackInstallerService cannot be null");
    this.packInstallRepository =
        Objects.requireNonNull(packInstallRepository, "PackInstallRepository cannot be null");
  }

  /**
   * Installs every shipped bundle the catalogue does not already carry.
   *
   * <p>Runs immediately at startup and then on the configured delay while anything is still
   * pending. The delay is wiring — how long to wait for a dependency that is still coming up — and
   * not a tuning knob for the catalogue.
   */
  @Scheduled(fixedDelayString = "${orazaka.studio-service.packs.bootstrap-interval:30000}")
  public void install() {
    if (settled.get()) {
      return;
    }
    List<Path> discovered = packBundleResolver.discover();
    int installed = 0;
    int pending = 0;
    for (Path directory : discovered) {
      PackBundle bundle;
      try {
        bundle = packBundleResolver.read(directory);
      } catch (RuntimeException malformed) {
        // Not retried: a manifest fault is not a dependency that is still starting.
        logger.error("Shipped bundle at {} cannot be read and was skipped", directory, malformed);
        continue;
      }
      if (packInstallRepository.isApplied(bundle)) {
        continue;
      }
      try {
        int studios = packInstallerService.install(bundle);
        installed++;
        logger.info(
            "Bootstrapped pack {} v{} — {} Studio(s)", bundle.key(), bundle.version(), studios);
      } catch (RuntimeException failure) {
        pending++;
        // The throwable, not its message. PackInstallerService wraps the real fault as the CAUSE of
        // a "failed and was rolled back" message that says nothing on its own, so logging
        // getMessage() left this loop retrying every 30 seconds, forever, without once saying why —
        // which is how a pack that could not install survived two e2e runs as an empty composer row
        // (ADR-069 §0.3).
        logger.warn(
            "Pack {} is shipped here and not installed yet — retrying", bundle.key(), failure);
      }
    }
    if (pending == 0) {
      settled.set(true);
      if (installed > 0) {
        logger.info("Pack bootstrap complete — {} bundle(s) installed", installed);
      }
    }
  }
}
