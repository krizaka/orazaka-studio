package com.orazaka.studioservice.infrastructure.adapter.rest.dto;

import com.orazaka.studioservice.domain.model.ComposerStudio;
import com.orazaka.studioservice.domain.model.LockReason;

/**
 * One button of the chat composer, as the client reads it.
 *
 * <p>What replaced a {@code BootstrapFeature}. The fields that have gone are the whole point: no
 * {@code uriPath}, no {@code httpMethod} and no {@code payloadTemplate}, because the client no
 * longer composes a call — it starts a run of a Studio and the blueprint says what happens next.
 *
 * @param studioKey the Studio a click launches a run of
 * @param label the label in the caller's locale, from the pack's i18n
 * @param iconKey the icon, from the same row
 * @param version the published blueprint version this button was derived from
 * @param capabilityKey the capability its single step dispatches to, for the model catalogue
 * @param inputKey the single input the composer fills
 * @param inputKind {@code TEXT} for what the user typed, {@code ASSET} for what they attached
 * @param promptKey where the composer's prose goes, or {@code null} when the schema has no place
 * @param available whether the actor may run it; a locked button is rendered and disabled
 * @param lockedReason why it is locked, or {@link LockReason#NONE}
 */
public record ComposerStudioResponse(
    String studioKey,
    String label,
    String iconKey,
    String version,
    String capabilityKey,
    String inputKey,
    String inputKind,
    String promptKey,
    boolean available,
    LockReason lockedReason) {

  /**
   * Maps the domain answer onto the wire.
   *
   * @param studio the composer entry
   * @return the response
   */
  public static ComposerStudioResponse from(ComposerStudio studio) {
    return new ComposerStudioResponse(
        studio.studioKey(),
        studio.label(),
        studio.iconKey(),
        studio.version(),
        studio.capabilityKey(),
        studio.inputKey(),
        studio.inputKind().name(),
        studio.promptKey(),
        !studio.locked(),
        studio.lockedReason());
  }
}
