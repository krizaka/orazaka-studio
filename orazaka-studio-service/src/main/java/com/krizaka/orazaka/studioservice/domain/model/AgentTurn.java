package com.krizaka.orazaka.studioservice.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One model turn of a client-executed agent (ADR-093 §4, §6): the conversation so far and the
 * closed list of tools the client runs. The engine answers with text or with the calls it wants
 * made; it never runs them.
 *
 * @param runId the Studio run the turn is a step of
 * @param actorId the opaque billable subject the engine gates, meters and role-checks
 * @param featureKey the routing key (ADR-029), chosen by the server from the Studio, never by the
 *     client
 * @param system the fixed system prompt of the client's template
 * @param messages the conversation; tool results are messages of role {@code tool}, data only
 * @param tools the tools the client offers
 */
public record AgentTurn(
    UUID runId,
    String actorId,
    String featureKey,
    String system,
    List<Message> messages,
    List<Tool> tools) {

  /** Compact canonical constructor (ERR-106). */
  public AgentTurn {
    Objects.requireNonNull(runId, "runId must not be null");
    Objects.requireNonNull(actorId, "actorId must not be null");
    Objects.requireNonNull(featureKey, "featureKey must not be null");
    system = system == null ? "" : system;
    messages = messages == null ? List.of() : List.copyOf(messages);
    tools = tools == null ? List.of() : List.copyOf(tools);
  }

  /**
   * A message of the conversation.
   *
   * @param role {@code user}, {@code assistant} or {@code tool}
   * @param content its text; for a {@code tool} message, the result as data
   * @param calls the calls an {@code assistant} message made
   * @param toolCallId on a {@code tool} message, the id of the call it answers
   */
  public record Message(String role, String content, List<Call> calls, String toolCallId) {
    /** Compact canonical constructor. */
    public Message {
      Objects.requireNonNull(role, "role must not be null");
      content = content == null ? "" : content;
      calls = calls == null ? List.of() : List.copyOf(calls);
      toolCallId = toolCallId == null ? "" : toolCallId;
    }
  }

  /**
   * A tool call the model made.
   *
   * @param id the id the model gave it
   * @param name the tool
   * @param argumentsJson the arguments as the model wrote them (data, never trusted)
   */
  public record Call(String id, String name, String argumentsJson) {
    /** Compact canonical constructor. */
    public Call {
      Objects.requireNonNull(name, "name must not be null");
      id = id == null ? "" : id;
      argumentsJson = argumentsJson == null ? "{}" : argumentsJson;
    }
  }

  /**
   * A tool the client runs.
   *
   * @param name its name
   * @param description what it does, for the model
   * @param inputSchema the JSON schema of its arguments
   */
  public record Tool(String name, String description, String inputSchema) {
    /** Compact canonical constructor. */
    public Tool {
      Objects.requireNonNull(name, "name must not be null");
      description = description == null ? "" : description;
      inputSchema = inputSchema == null || inputSchema.isBlank() ? "{}" : inputSchema;
    }
  }
}
