package com.orazaka.studioservice.application.service;

import com.orazaka.billing.domain.model.BillableCapability;
import com.orazaka.billing.domain.model.CreditHoldCommand;
import com.orazaka.billing.domain.model.CreditHoldResponse;
import com.orazaka.billing.domain.model.MeteredStep;
import com.orazaka.billing.domain.port.CreditAuthorizationClient;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * One credit hold per run — the whole run's authorisation, taken once (ADR-034 §4).
 *
 * <p>Per-step holds are deliberately rejected: discovering mid-run that step five is unaffordable
 * is a worse product than a slightly conservative estimate, and the measured settle corrects the
 * drift anyway. The blueprint's {@code estimatedCredits} is what gets reserved.
 *
 * <p><b>The hold is a guarantee of solvency, and at the end it becomes the debit</b> — settled once
 * against the sum of what the run's steps actually measured, each priced at its own capability's
 * rate. That is ADR-034 §4's protocol, and until ADR-041 it was not what happened: this class's
 * {@code settle} was a {@code release}, and its javadoc said each step settled its own consumption.
 * No step ever did. Every step carried the RUN's hold, so the first one to finish settled the whole
 * run at the AGENT/CALL rate — a hardcoded quantity of 1 — and the rest ran free. 480 reserved, 5
 * debited. The comment was not merely out of date; it described a mechanism that never existed, and
 * it is the reason the defect survived three phases of people reading this file.
 *
 * <p>Settlement and release are best-effort and never propagate: a run that finished must not be
 * reported as failed because the ledger was briefly unreachable. The hold sweeper on the billing
 * side is what closes a reservation this call could not — which is why leaving it outstanding is
 * recoverable and reporting a false failure is not.
 */
@Service
public class CreditReservationService {

  private static final Logger logger = LoggerFactory.getLogger(CreditReservationService.class);

  /**
   * A run is metered as an agent-style orchestration: it is a roll-up of the steps it spawns, each
   * of which is separately metered by the executor that runs it.
   */
  private static final BillableCapability RUN_CAPABILITY = BillableCapability.AGENT;

  private final CreditAuthorizationClient creditAuthorizationClient;

  public CreditReservationService(CreditAuthorizationClient creditAuthorizationClient) {
    this.creditAuthorizationClient =
        Objects.requireNonNull(creditAuthorizationClient, "CreditAuthorizationClient required");
  }

  /**
   * Reserves a run's estimated cost before any step is submitted.
   *
   * @param actorId the opaque billable subject
   * @param correlationId ties this hold to the run and every job it spawns
   * @param estimatedCredits the blueprint's estimate
   * @return the hold id to settle or release
   * @throws com.orazaka.billing.domain.exception.InsufficientCreditsException when the actor cannot
   *     cover it under active enforcement — no compute starts without a hold
   */
  public String hold(String actorId, String correlationId, long estimatedCredits) {
    CreditHoldResponse response =
        creditAuthorizationClient.hold(
            new CreditHoldCommand(
                actorId, RUN_CAPABILITY, null, correlationId, null, estimatedCredits));
    return response.holdId();
  }

  /**
   * Closes a run's hold against the sum of what its steps actually consumed.
   *
   * <p>Each step is priced against its OWN {@code (capability, model)} row — a script step as CHAT,
   * a still as IMAGE, a clip as VIDEO — and the credits are summed into one debit. The pricing
   * happens in the billing service, at the pricebook version the hold pinned: this context knows
   * what was measured and what produced it, never what it costs (ADR-041).
   *
   * <p>A run that measured nothing is <b>released</b>, not billed at its estimate. That is the same
   * direction {@code JobSettlementListener} chose for a single job, and the honest one: charging a
   * guess would hide a reporting gap behind revenue.
   *
   * @param holdId the reservation, {@code null} when the run was not metered
   * @param runId the run, for the audit trail
   * @param steps what each terminal step measured, and what prices it
   */
  public void settle(String holdId, UUID runId, List<MeteredStep> steps) {
    if (holdId == null || holdId.isBlank()) {
      return;
    }
    if (steps == null || steps.isEmpty()) {
      release(holdId, "run " + runId + " completed but measured nothing");
      return;
    }
    try {
      // The run id is the idempotency key: a run settles exactly once, and a redelivered
      // terminal-transition message must not debit a second time.
      if (!creditAuthorizationClient.settleAggregate(holdId, steps, "studio-run-" + runId)) {
        release(holdId, "run " + runId + " completed; nothing its steps measured could be priced");
      }
    } catch (RuntimeException unreachable) {
      // Same contract as release(): a run that finished must never be reported as failed because
      // the ledger was briefly unreachable. The hold is left to the billing sweeper, which is the
      // recoverable end of this failure.
      logger.warn(
          "Could not settle hold {} for run {}; leaving it to the sweeper",
          holdId,
          runId,
          unreachable);
    }
  }

  /**
   * Closes a run's hold with no debit.
   *
   * @param holdId the reservation, {@code null} when the run was not metered
   * @param reason why, retained for audit
   */
  public void release(String holdId, String reason) {
    if (holdId == null || holdId.isBlank()) {
      return;
    }
    try {
      creditAuthorizationClient.release(holdId, reason);
    } catch (RuntimeException unreachable) {
      // Never fail a finished run on a billing outage: the billing sweeper expires the hold at TTL.
      logger.warn("Could not close hold {} ({}); leaving it to the sweeper", holdId, reason);
    }
  }
}
