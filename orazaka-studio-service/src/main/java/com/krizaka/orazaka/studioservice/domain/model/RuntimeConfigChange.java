package com.krizaka.orazaka.studioservice.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * One audited change of a runtime setting: who changed which key, when, from what to what.
 *
 * @param at when the change was committed
 * @param actorId the administrator's id (never a name or an e-mail)
 * @param key the setting
 * @param before the stored value before the change
 * @param after the stored value after it
 */
public record RuntimeConfigChange(
    Instant at, String actorId, String key, String before, String after) {

  public RuntimeConfigChange {
    Objects.requireNonNull(at, "at must not be null");
    Objects.requireNonNull(actorId, "actorId must not be null");
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(before, "before must not be null");
    Objects.requireNonNull(after, "after must not be null");
  }
}
