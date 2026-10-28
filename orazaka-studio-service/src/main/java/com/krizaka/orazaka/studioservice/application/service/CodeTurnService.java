package com.krizaka.orazaka.studioservice.application.service;

import com.krizaka.orazaka.studio.domain.model.RunStatus;
import com.krizaka.orazaka.studio.domain.model.Studio;
import com.krizaka.orazaka.studioservice.domain.exception.RunNotFoundException;
import com.krizaka.orazaka.studioservice.domain.exception.StudioNotFoundException;
import com.krizaka.orazaka.studioservice.domain.model.AgentTurn;
import com.krizaka.orazaka.studioservice.domain.model.AgentTurnResult;
import com.krizaka.orazaka.studioservice.domain.model.RunDetail;
import com.krizaka.orazaka.studioservice.domain.port.AgentTurnClient;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The model turn of the code agent (ADR-093 §3, §6): a step of a Studio run that the Mac app
 * drives, one request per turn.
 *
 * <p>One gate, in this service, so no route can skip it: the run must be the caller's and be a
 * Studio of the Contributor pack, still in flight, and the actor must be entitled to that Studio
 * <b>on every turn</b> (a grant revoked or a plan lapsed ends the session at its next turn). The
 * routing key is chosen here from the Studio, never read from the client, and the engine applies
 * the per-tool roles (ADR-091) to the tools offered.
 */
@Service
public class CodeTurnService {

  /** The Studios of the Contributor pack and the routing key (ADR-029) of each. */
  private static final Map<String, String> FEATURE_KEYS =
      Map.of(
          "code-issue-to-pr", "agent.code.issue-to-pr",
          "code-read-write", "agent.code.read-write");

  private static final Set<RunStatus> IN_FLIGHT =
      Set.of(RunStatus.RUNNING, RunStatus.AWAITING_INPUT);

  private static final Set<String> ROLES = Set.of("user", "assistant", "tool");

  // @fixed: the ceilings of the Contributor Studios' input schema (messages 400, tools 12).
  static final int MAX_MESSAGES = 400;
  static final int MAX_TOOLS = 12;

  // @fixed: the system prompt is a short fixed template; a long one is not the Mac's.
  static final int MAX_SYSTEM_CHARS = 20_000;

  private final StudioRunService runs;
  private final StudioCatalogService catalog;
  private final StudioAccessService access;
  private final AgentTurnClient engine;

  /**
   * @param runs the runs, scoped to their owner
   * @param catalog the Studios
   * @param access the entitlement decision, read on every turn
   * @param engine the model turn
   */
  public CodeTurnService(
      StudioRunService runs,
      StudioCatalogService catalog,
      StudioAccessService access,
      AgentTurnClient engine) {
    this.runs = Objects.requireNonNull(runs, "StudioRunService cannot be null");
    this.catalog = Objects.requireNonNull(catalog, "StudioCatalogService cannot be null");
    this.access = Objects.requireNonNull(access, "StudioAccessService cannot be null");
    this.engine = Objects.requireNonNull(engine, "AgentTurnClient cannot be null");
  }

  /**
   * Runs one turn.
   *
   * @param runId the Studio run
   * @param actorId the opaque billable subject
   * @param system the fixed system prompt
   * @param messages the conversation, tool results included as {@code tool} messages
   * @param tools the tools the client offers
   * @return what to do next: the model's text and the calls the client must run
   * @throws RunNotFoundException when the run is not the caller's, or is not a Contributor run
   * @throws IllegalStateException when the run is no longer in flight
   * @throws IllegalArgumentException when the turn is malformed or over a ceiling
   * @throws com.krizaka.orazaka.studio.domain.exception.StudioNotEntitledException when the actor
   *     no longer holds the Studio
   */
  public AgentTurnResult turn(
      UUID runId,
      String actorId,
      String system,
      List<AgentTurn.Message> messages,
      List<AgentTurn.Tool> tools) {
    RunDetail run = runs.find(runId, actorId).orElseThrow(() -> new RunNotFoundException(runId));
    String featureKey = FEATURE_KEYS.get(run.studioKey());
    if (featureKey == null) {
      // Not a code run: the same answer as a run that is not yours, nothing is disclosed.
      throw new RunNotFoundException(runId);
    }
    Studio studio =
        catalog
            .find(run.studioKey(), null)
            .orElseThrow(() -> new StudioNotFoundException(run.studioKey()));
    access.requireEntitled(studio, actorId);
    if (!IN_FLIGHT.contains(run.status())) {
      throw new IllegalStateException("The run is " + run.status() + ": it takes no more turns");
    }
    validate(system, messages, tools);
    return engine.turn(new AgentTurn(runId, actorId, featureKey, system, messages, tools));
  }

  private static void validate(
      String system, List<AgentTurn.Message> messages, List<AgentTurn.Tool> tools) {
    if (system != null && system.length() > MAX_SYSTEM_CHARS) {
      throw new IllegalArgumentException("system is too long");
    }
    if (messages == null || messages.isEmpty() || messages.size() > MAX_MESSAGES) {
      throw new IllegalArgumentException("messages must hold 1 to " + MAX_MESSAGES + " entries");
    }
    if (tools == null || tools.size() > MAX_TOOLS) {
      throw new IllegalArgumentException("tools must hold at most " + MAX_TOOLS + " entries");
    }
    for (AgentTurn.Message message : messages) {
      if (!ROLES.contains(message.role())) {
        throw new IllegalArgumentException("unknown message role: " + message.role());
      }
      if ("tool".equals(message.role()) && message.toolCallId().isBlank()) {
        throw new IllegalArgumentException("a tool message must name the call it answers");
      }
    }
  }
}
