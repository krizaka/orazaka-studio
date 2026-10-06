package com.orazaka.studioservice.domain.model;

import com.orazaka.studio.domain.model.PackKind;

/**
 * How this actor gets this Pack — the marketplace band, decided on the server.
 *
 * <p><b>Keyed on {@code kind} AND the entitlement snapshot.</b> The client read {@code kind ===
 * "TOOLKIT"} alone and printed "included" with no button, which told an actor who is <b>not</b>
 * entitled that they already have the pack, and then gave them no way to get it. That is the mirror
 * of the state M1 deleted — a pack that looked installable because a row existed — and it is the
 * same lie pointed the other way (ADR-061, ADR-066).
 *
 * <p>Three states, and the question each answers is <i>acquisition</i>, never ownership: a VERTICAL
 * the actor already bought renders from their holdings as it always has, and its band stays {@link
 * #BUYABLE} because buying is still how that pack is acquired.
 */
public enum PackAccess {

  /**
   * Entitlement already grants it and no purchase applies: a TOOLKIT whose Studios the actor may
   * open. Its installation is derived and never stored, so there is nothing to add.
   */
  INCLUDED,

  /** Not granted, and billing named a price: the card offers the purchase. */
  BUYABLE,

  /**
   * Not granted and no price to show. Billing did not answer, so the card says so rather than
   * offering an amount nobody quoted — an invented price is one the product would be held to
   * (ADR-036 §4.4).
   */
  UNAVAILABLE;

  /**
   * Decides the band from what the pack is, what the actor's entitlements grant, and what billing
   * quoted.
   *
   * @param kind VERTICAL or TOOLKIT
   * @param entitled whether the actor may already open everything this pack bundles
   * @param priceCents billing's answer, or {@code null} when it did not answer
   * @return the band
   */
  public static PackAccess of(PackKind kind, boolean entitled, Integer priceCents) {
    if (kind == PackKind.TOOLKIT && entitled) {
      return INCLUDED;
    }
    return priceCents == null ? UNAVAILABLE : BUYABLE;
  }
}
