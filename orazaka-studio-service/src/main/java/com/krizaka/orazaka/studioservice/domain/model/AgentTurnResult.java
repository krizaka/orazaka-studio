package com.krizaka.orazaka.studioservice.domain.model;

import java.util.List;

/**
 * What the engine answered for one {@link AgentTurn}: text, the tool calls it screened against the
 * tools offered and the roles of the actor (ADR-091), and what the turn consumed.
 *
 * @param text the model's text, possibly empty when it only asked for tools
 * @param calls the calls the client must run, empty when the model is done
 * @param promptTokens tokens read
 * @param completionTokens tokens written
 * @param creditsMicro credits settled for the turn, in millionths
 */
public record AgentTurnResult(
    String text,
    List<AgentTurn.Call> calls,
    long promptTokens,
    long completionTokens,
    long creditsMicro) {

  /** Compact canonical constructor. */
  public AgentTurnResult {
    text = text == null ? "" : text;
    calls = calls == null ? List.of() : List.copyOf(calls);
  }
}
