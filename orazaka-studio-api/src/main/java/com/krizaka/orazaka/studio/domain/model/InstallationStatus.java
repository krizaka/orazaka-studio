package com.krizaka.orazaka.studio.domain.model;

/**
 * Lifecycle of one actor's copy of a Studio.
 *
 * <p>Nothing here deletes. A lapsed subscription pauses an installation and an uninstall revokes
 * it, so that resubscribing or reinstalling restores the actor's configuration instead of asking
 * them to fill the install dialog in again.
 */
public enum InstallationStatus {

  /** Installed, configured, runnable. */
  ACTIVE,

  /**
   * Suspended without losing configuration — typically because the entitlement that granted it
   * lapsed. Runs are refused; the config survives.
   */
  PAUSED,

  /**
   * Runnable on the pinned version, with a newer published version available. Upgrades are
   * <b>explicit</b>: this state raises a banner, it never migrates the pin (ADR-034 §5).
   */
  UPGRADE_AVAILABLE,

  /** Uninstalled. Soft state — the retention job purges the rows and generated assets later. */
  REVOKED
}
