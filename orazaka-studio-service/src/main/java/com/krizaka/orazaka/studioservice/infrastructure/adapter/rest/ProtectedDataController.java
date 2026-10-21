package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.studioservice.application.service.ProtectedDataService;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto.ConsentNoticeResponse;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto.ErasureResponse;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A regulated pack's consent notice, and the right to take the data back (orazaka#301).
 *
 * <p>No {@code @PreAuthorize}: the authorisation that matters is ownership, enforced as a row
 * predicate on the actor id taken from the verified token - never from the URL or the body, so
 * there is no id one person can change to erase somebody else's data.
 */
@RestController
@RequestMapping("/api/v1/studios")
class ProtectedDataController {

  private static final String DEFAULT_LOCALE = "fr";

  private final ProtectedDataService protectedData;

  ProtectedDataController(ProtectedDataService protectedData) {
    this.protectedData = Objects.requireNonNull(protectedData, "ProtectedDataService required");
  }

  /**
   * What a person agrees to, in their language, with the verified crisis line of each region.
   *
   * @param packKey the pack
   * @param locale the caller's language; an unknown one gets the default statement
   * @return the notice; {@code 404} when the pack asks for no consent
   */
  @GetMapping("/packs/{packKey}/consent")
  ConsentNoticeResponse consent(
      @PathVariable String packKey, @RequestParam(defaultValue = DEFAULT_LOCALE) String locale) {
    return ConsentNoticeResponse.from(protectedData.consentNotice(packKey, locale));
  }

  /**
   * Erases everything the pack holds of the caller: entries, inputs and outputs, files,
   * installations and the consent they recorded. Idempotent.
   *
   * @param packKey the pack
   * @param actor the authenticated caller - the only person whose data is touched
   * @return {@code 200} with counts; {@code 503} when the job plane could not erase its part, with
   *     nothing deleted
   */
  @DeleteMapping("/packs/{packKey}/my-data")
  ErasureResponse eraseAll(@PathVariable String packKey, @AuthenticationPrincipal Jwt actor) {
    return ErasureResponse.from(protectedData.eraseAll(actor.getSubject(), packKey));
  }

  /**
   * Erases one entry of the caller's: a run of a protected pack, with its inputs, outputs and
   * files. Idempotent, and the same answer whether the entry existed, was somebody else's or is
   * already gone.
   *
   * @param runId the entry
   * @param actor the authenticated caller
   * @return {@code 204}
   */
  @DeleteMapping("/runs/{runId}")
  ResponseEntity<Void> eraseEntry(@PathVariable UUID runId, @AuthenticationPrincipal Jwt actor) {
    protectedData.eraseEntry(actor.getSubject(), runId);
    return ResponseEntity.noContent().build();
  }
}
