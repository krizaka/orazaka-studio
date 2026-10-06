package com.orazaka.studio.domain.model;

/**
 * What a pack needs from the platform in order to run (ADR-037 §3.1).
 *
 * <p>The tier is what tells an installer how much of a bundle it can honour. Phase D installs
 * {@link #DATA} packs completely; the other two are declared so that a manifest written today does
 * not have to be rewritten when the phases that serve them land.
 */
public enum PackTier {
  /** Reuses existing capabilities and ships no code — rows and prompts only. */
  DATA,
  /** Adds in-process executors, discovered through {@code AutoConfiguration.imports}. */
  CAPABILITY,
  /** Brings its own out-of-process worker, which honours the AMQP contract. */
  WORKER
}
