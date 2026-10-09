package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest;

import com.krizaka.billing.domain.exception.InsufficientCreditsException;
import com.krizaka.orazaka.studio.domain.exception.BlueprintValidationException;
import com.krizaka.orazaka.studio.domain.exception.StudioNotEntitledException;
import com.krizaka.orazaka.studioservice.domain.exception.ConsentRequiredException;
import com.krizaka.orazaka.studioservice.domain.exception.InstallationNotFoundException;
import com.krizaka.orazaka.studioservice.domain.exception.PackInstallException;
import com.krizaka.orazaka.studioservice.domain.exception.RunNotFoundException;
import com.krizaka.orazaka.studioservice.domain.exception.StudioIncludedException;
import com.krizaka.orazaka.studioservice.domain.exception.StudioNotFoundException;
import com.krizaka.orazaka.studioservice.domain.exception.StudioNotInstalledException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * REST exception advice for the studio service.
 *
 * <p>The three refusals are deliberately three different statuses, because they are three different
 * products (ADR-034 §4, §8): {@code 404} the Studio does not exist, {@code 409} it exists and must
 * be bought, {@code 402} it is owned but unaffordable right now. A UI given one status for all
 * three can only offer the right remedy a third of the time.
 */
@RestControllerAdvice(
    basePackages = "com.krizaka.orazaka.studioservice.infrastructure.adapter.rest")
public class RestErrorResolver {

  private static final Logger logger = LoggerFactory.getLogger(RestErrorResolver.class);
  private static final String STATUS_KEY = "status";
  private static final String MESSAGE_KEY = "message";

  /**
   * Maps an unknown Studio key to {@code 404}.
   *
   * @param ex the lookup that resolved to nothing
   * @return {@code 404} naming the key
   */
  @ExceptionHandler(StudioNotFoundException.class)
  public ResponseEntity<Map<String, Object>> handleNotFound(StudioNotFoundException ex) {
    logger.info("Studio not found: {}", ex.studioKey());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(Map.of(STATUS_KEY, "studio_not_found", MESSAGE_KEY, ex.getMessage()));
  }

  /**
   * Maps an installation the caller does not own to {@code 404}.
   *
   * <p>The same answer as an installation that does not exist, deliberately: distinguishing them
   * would confirm the existence of another tenant's row, which is the IDOR this design cannot
   * afford (ADR-034 §18).
   *
   * @param ex the lookup that resolved to nothing for this actor
   * @return {@code 404}
   */
  @ExceptionHandler(InstallationNotFoundException.class)
  public ResponseEntity<Map<String, Object>> handleInstallationNotFound(
      InstallationNotFoundException ex) {
    logger.info("Installation not visible to caller: {}", ex.installationId());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(Map.of(STATUS_KEY, "installation_not_found", MESSAGE_KEY, ex.getMessage()));
  }

  /**
   * Maps a run the caller does not own to {@code 404}, like its installation counterpart.
   *
   * @param ex the lookup that resolved to nothing for this actor
   * @return {@code 404}
   */
  @ExceptionHandler(RunNotFoundException.class)
  public ResponseEntity<Map<String, Object>> handleRunNotFound(RunNotFoundException ex) {
    logger.info("Run not visible to caller: {}", ex.runId());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(Map.of(STATUS_KEY, "run_not_found", MESSAGE_KEY, ex.getMessage()));
  }

  /**
   * Maps a run refused by a live limit to {@code 409}.
   *
   * <p>The concurrency cap and a paused installation are conflicts with the actor's current state,
   * not malformed requests: the same call succeeds once a run finishes or a subscription resumes.
   *
   * @param ex the refusal
   * @return {@code 409} explaining what state blocks it
   */
  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<Map<String, Object>> handleConflict(IllegalStateException ex) {
    logger.info("Run refused by current state: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(Map.of(STATUS_KEY, "run_refused", MESSAGE_KEY, String.valueOf(ex.getMessage())));
  }

  /**
   * Maps a rejected configuration to {@code 400}.
   *
   * @param ex the validation failure — an unknown config key, typically
   * @return {@code 400} naming what was wrong
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, Object>> handleInvalidArgument(IllegalArgumentException ex) {
    logger.info("Rejected request: {}", ex.getMessage());
    return ResponseEntity.badRequest()
        .body(Map.of(STATUS_KEY, "invalid_request", MESSAGE_KEY, String.valueOf(ex.getMessage())));
  }

  /**
   * Maps an entitlement refusal to {@code 409}, carrying the pack that unlocks it.
   *
   * <p>{@code 409} rather than {@code 403} on purpose: the request is not forbidden, it conflicts
   * with what the actor currently owns, and the body says exactly what would resolve the conflict
   * so the client can open checkout (ADR-034 §8.1).
   *
   * @param ex the refusal
   * @return {@code 409} with the entitlement key and, when purchasable, the pack
   */
  @ExceptionHandler(StudioNotEntitledException.class)
  public ResponseEntity<Map<String, Object>> handleNotEntitled(StudioNotEntitledException ex) {
    logger.info("Studio {} refused: {} not granted", ex.studioKey(), ex.entitlementKey());
    return studioUnavailable(
        "studio_not_entitled",
        ex.getMessage(),
        ex.studioKey(),
        ex.entitlementKey(),
        ex.packKey(),
        ex.packKey() == null ? List.of("upgrade_plan") : List.of("buy_package"));
  }

  /**
   * Maps a run of an uninstalled VERTICAL to {@code 409}, in the entitlement refusal's shape.
   *
   * <p>The same status, the same keys, the same pack to open (ADR-061). A client handles "not
   * entitled" and "not installed" with one branch — open the pack — and the page it opens decides
   * whether that means buy or install; only {@code status} and {@code remedies} say which.
   *
   * @param ex the refusal
   * @return {@code 409} with the pack to open and {@code install} as the remedy
   */
  @ExceptionHandler(StudioNotInstalledException.class)
  public ResponseEntity<Map<String, Object>> handleNotInstalled(StudioNotInstalledException ex) {
    logger.info("Studio {} refused: not installed", ex.studioKey());
    return studioUnavailable(
        "studio_not_installed",
        ex.getMessage(),
        ex.studioKey(),
        ex.entitlementKey(),
        ex.packKey(),
        List.of("install"));
  }

  /**
   * Maps an install of a TOOLKIT to {@code 409}: nothing to install, run it instead (ADR-061).
   *
   * @param ex the refusal
   * @return {@code 409} with the TOOLKIT pack and {@code run} as the remedy
   */
  @ExceptionHandler(StudioIncludedException.class)
  public ResponseEntity<Map<String, Object>> handleIncluded(StudioIncludedException ex) {
    logger.info("Studio {} is included in {}: install refused", ex.studioKey(), ex.packKey());
    return studioUnavailable(
        "studio_included",
        ex.getMessage(),
        ex.studioKey(),
        ex.entitlementKey(),
        ex.packKey(),
        List.of("run"));
  }

  /**
   * The one body every "this Studio, for this actor" refusal answers with.
   *
   * <p>A single builder rather than three copies, because the shape being identical is the
   * contract: a client with one branch for these refusals is only correct while no handler grows a
   * key the others lack. Keys are always present, {@code packKey} included when it is null.
   */
  private static ResponseEntity<Map<String, Object>> studioUnavailable(
      String status,
      String message,
      String studioKey,
      String entitlementKey,
      String packKey,
      List<String> remedies) {
    Map<String, Object> body = new HashMap<>();
    body.put(STATUS_KEY, status);
    body.put(MESSAGE_KEY, message);
    body.put("studioKey", studioKey);
    body.put("entitlementKey", entitlementKey);
    body.put("packKey", packKey);
    body.put("remedies", remedies);
    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
  }

  /**
   * Maps a missing or stale consent to a {@code 451} carrying the statement still to be agreed to.
   *
   * <p>Not a {@code 400}: the caller is not wrong, they have not been asked yet. The response
   * carries the version and the statement so a client can render exactly what has to be consented
   * to, rather than inventing wording of its own for a legal notice (ADR-055 §3).
   *
   * @param ex the refusal
   * @return {@code 451} with the version and the statement
   */
  @ExceptionHandler(ConsentRequiredException.class)
  public ResponseEntity<Map<String, Object>> handleConsentRequired(ConsentRequiredException ex) {
    // The Studio, never the actor: who asked to install a wellbeing pack is exactly the fact this
    // pack's whole regulatory class exists to keep out of an operations log.
    logger.info("Studio {} requires consent version {}", ex.studioKey(), ex.requiredVersion());
    Map<String, Object> body = new HashMap<>();
    body.put(STATUS_KEY, "consent_required");
    body.put(MESSAGE_KEY, ex.getMessage());
    body.put("studioKey", ex.studioKey());
    body.put("consentVersion", ex.requiredVersion());
    body.put("statement", ex.statement());
    return ResponseEntity.status(HttpStatus.UNAVAILABLE_FOR_LEGAL_REASONS).body(body);
  }

  /**
   * Maps an unaffordable run to the structured {@code 402} of the billing design.
   *
   * @param ex the refusal raised while holding the run's estimate
   * @return {@code 402} with the numbers the ledger actually refused on
   */
  @ExceptionHandler(InsufficientCreditsException.class)
  public ResponseEntity<Map<String, Object>> handleInsufficientCredits(
      InsufficientCreditsException ex) {
    logger.info("Run refused: required {}, available {}", ex.required(), ex.available());
    return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
        .body(
            Map.of(
                STATUS_KEY,
                "insufficient_credits",
                "capability",
                ex.capability().name(),
                "required",
                ex.required(),
                "balance",
                ex.available(),
                "remedies",
                ex.remedies()));
  }

  /**
   * Maps a blueprint that cannot be statically validated to {@code 400}, naming the offending node.
   *
   * @param ex the validation failure
   * @return {@code 400} with the step id so the authoring UI can highlight it
   */
  @ExceptionHandler(BlueprintValidationException.class)
  public ResponseEntity<Map<String, Object>> handleInvalidBlueprint(
      BlueprintValidationException ex) {
    logger.warn("Blueprint rejected: {}", ex.getMessage());
    Map<String, Object> body = new HashMap<>();
    body.put(STATUS_KEY, "invalid_blueprint");
    body.put(MESSAGE_KEY, ex.getMessage());
    body.put("stepId", ex.stepId());
    return ResponseEntity.badRequest().body(body);
  }

  /**
   * Maps a refused or rolled-back pack install to {@code 422}, carrying the reason as text.
   *
   * <p>422 rather than 400: the request was well-formed — its shape passed {@code pack.schema.json}
   * before it was sent — and what failed is semantic, a capability this platform cannot route or a
   * slice that refused the write. The caller is an operator at a terminal, so the message is the
   * payload; the exception's own text names the capability or the slice.
   *
   * @param ex the install failure
   * @return {@code 422} with the reason
   */
  @ExceptionHandler(PackInstallException.class)
  public ResponseEntity<Map<String, Object>> handleFailedInstall(PackInstallException ex) {
    // The CAUSE is the diagnosis, and the message alone is not. "failed and was rolled back" told
    // an operator nothing about which slice refused: the first run of this handler produced a 422
    // whose reason had to be recovered by reading three services' logs. The cause is logged with
    // its stack and its text is returned, because the caller is at a terminal.
    Throwable cause = ex.getCause();
    logger.error("Pack install refused: {}", ex.getMessage(), ex);
    Map<String, Object> body = new HashMap<>();
    body.put(STATUS_KEY, "pack_install_failed");
    body.put(MESSAGE_KEY, ex.getMessage());
    if (cause != null) {
      body.put("cause", cause.getClass().getSimpleName() + ": " + cause.getMessage());
    }
    return ResponseEntity.unprocessableEntity().body(body);
  }
}
