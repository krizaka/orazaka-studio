package com.krizaka.orazaka.studioservice.domain.port;

import com.krizaka.orazaka.studioservice.domain.model.AgentTurn;
import com.krizaka.orazaka.studioservice.domain.model.AgentTurnResult;

/**
 * Outbound port: the model turn of a client-executed agent, run by the AI engine (ADR-093 §6).
 *
 * <p>The engine owns routing (ADR-029), the per-tool roles (ADR-091), metering and the content
 * policy; the Studio owns the run and the pack gate. Neither the routing nor a provider is named
 * here.
 */
public interface AgentTurnClient {

  /**
   * Runs one turn.
   *
   * @param turn the conversation and the tools the client offers
   * @return the engine's answer
   * @throws com.krizaka.orazaka.studioservice.domain.exception.AgentTurnRefusedException when the
   *     engine refuses the turn (consent, credits, entitlement), carrying its own code
   */
  AgentTurnResult turn(AgentTurn turn);
}
