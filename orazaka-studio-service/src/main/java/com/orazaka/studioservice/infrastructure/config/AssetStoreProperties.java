package com.orazaka.studioservice.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * What this deployment guarantees about the bytes a run reads and writes ({@code
 * orazaka.studio-service.assets}).
 *
 * <p>Both values are declarations the platform operator is accountable for, not facts the service
 * can observe: a process cannot tell whether the filesystem beneath it is encrypted, and asking it
 * to guess is exactly the inference AGENTS.md §12 forbids. So both default to the answer that
 * refuses: {@code encrypted-at-rest=false} and {@code deployment=HOSTED}. A local machine says so
 * in one line of its own yaml; a hosted deployment that says nothing is treated as a hosted
 * deployment with plaintext assets, which is what it is until someone claims otherwise.
 *
 * @param encryptedAtRest whether the asset store encrypts what it holds — the application-level
 *     envelope of ADR-051 §7 option B, not a volume the operating system happens to have unlocked
 * @param deployment where this instance runs; {@code LOCAL} is a single developer machine whose
 *     disk is the developer's own
 */
@ConfigurationProperties(prefix = "orazaka.studio-service.assets")
public record AssetStoreProperties(
    @DefaultValue("false") boolean encryptedAtRest, @DefaultValue("HOSTED") Deployment deployment) {

  /** Where an instance runs, as declared by whoever runs it. */
  public enum Deployment {
    /** A developer's own machine: the disk, the operator and the data subject are one person. */
    LOCAL,
    /** Anything else — staging, production, a colleague's box, a container in someone's cloud. */
    HOSTED
  }

  /** Compact canonical constructor enforcing the wiring's invariants (ERR-106). */
  public AssetStoreProperties {
    if (deployment == null) {
      throw new IllegalArgumentException("assets deployment is required");
    }
  }

  /**
   * Whether this deployment may hold a protected pack's documents.
   *
   * <p>True when the store encrypts them, or when the only disk involved belongs to the person
   * whose documents they are.
   */
  public boolean mayHoldProtectedDocuments() {
    return encryptedAtRest || deployment == Deployment.LOCAL;
  }
}
