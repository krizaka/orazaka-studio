package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

import com.krizaka.orazaka.studioservice.domain.model.InstallConsent;
import java.util.Map;

/**
 * The install-dialog submission: the actor's answers to the Studio's config schema.
 *
 * <p>The pinned version is deliberately not a field. A fresh install always pins the newest
 * published version, and letting a client choose would make "install an old version" a supported
 * operation nothing in the product asks for — while opening the door to pinning a draft.
 *
 * @param config the answers, keyed by config-schema property; {@code null} is read as none
 */
public record InstallRequest(
    Map<String, String> config, String consentVersion, Boolean ageAttested, String region) {

  /** Compact canonical constructor: absent configuration is empty, never null (ERR-106). */
  public InstallRequest {
    config = config == null ? Map.of() : Map.copyOf(config);
  }

  /**
   * Convenience for a pack that requires nothing beyond a configuration.
   *
   * @param config install-time configuration
   */
  public InstallRequest(Map<String, String> config) {
    this(config, null, null, null);
  }

  /**
   * What this request declares for a {@code REGULATED} pack, or {@code null} when it declares
   * nothing.
   *
   * <p>Absent is not "no", it is "not asked" — and the installer refuses either way. A body with no
   * consent block installing a REGULATED pack gets back the statement it has to agree to (ADR-055
   * §3).
   *
   * @return the declaration, or {@code null}
   */
  public InstallConsent consent() {
    return consentVersion == null && ageAttested == null && region == null
        ? null
        : new InstallConsent(consentVersion, Boolean.TRUE.equals(ageAttested), region);
  }
}
