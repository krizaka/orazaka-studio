package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.krizaka.orazaka.studioservice.domain.model.AgentTurn;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * One model turn as the Mac app sends it (ADR-093 §6): the fixed system prompt, the conversation
 * (tool results come back as {@code tool} messages) and the closed list of tools it runs. Unknown
 * fields (the tool's {@code effect}, a client-side feature key) are ignored: the routing key is the
 * server's.
 *
 * @param system the fixed system prompt
 * @param messages the conversation so far
 * @param tools the tools the client offers
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TurnRequest(String system, List<MessageDto> messages, List<ToolDto> tools) {

  /**
   * @param role {@code user}, {@code assistant} or {@code tool}
   * @param content the text
   * @param calls the calls an assistant message made
   * @param toolCallId on a tool message, the call it answers
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record MessageDto(
      String role,
      String content,
      List<CallDto> calls,
      @JsonProperty("tool_call_id") @JsonAlias("toolCallId") String toolCallId) {}

  /**
   * @param id the call id
   * @param name the tool
   * @param args the arguments, a JSON object
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CallDto(String id, String name, JsonNode args) {

    /**
     * @return the domain call
     */
    public AgentTurn.Call toDomain() {
      return new AgentTurn.Call(id, name, args == null ? "{}" : args.toString());
    }
  }

  /**
   * @param name the tool
   * @param description what it does
   * @param parameters the JSON schema of the arguments
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ToolDto(String name, String description, JsonNode parameters) {}

  /**
   * @return the domain messages
   */
  public List<AgentTurn.Message> domainMessages() {
    return messages == null
        ? List.of()
        : messages.stream()
            .map(
                m ->
                    new AgentTurn.Message(
                        m.role(),
                        m.content(),
                        m.calls() == null
                            ? List.of()
                            : m.calls().stream().map(CallDto::toDomain).toList(),
                        m.toolCallId()))
            .toList();
  }

  /**
   * @return the domain tools
   */
  public List<AgentTurn.Tool> domainTools() {
    return tools == null
        ? List.of()
        : tools.stream()
            .map(
                t ->
                    new AgentTurn.Tool(
                        t.name(),
                        t.description(),
                        t.parameters() == null ? "{}" : t.parameters().toString()))
            .toList();
  }
}
