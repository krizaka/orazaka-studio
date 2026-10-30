package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

import com.krizaka.orazaka.studioservice.domain.model.AgentTurnResult;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The answer to one turn: what the model said, the calls the Mac must run (never run here), and
 * what the turn consumed.
 *
 * @param text the model's text
 * @param calls the tool calls to run, empty when the model is done
 * @param usage tokens and credits of the turn
 */
public record TurnResponse(String text, List<CallDto> calls, UsageDto usage) {

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * @param id the call id the result must name back
   * @param name the tool
   * @param args the arguments as the model wrote them
   */
  public record CallDto(String id, String name, JsonNode args) {}

  /**
   * @param promptTokens tokens read
   * @param completionTokens tokens written
   * @param creditsMicro credits settled, in millionths
   */
  public record UsageDto(long promptTokens, long completionTokens, long creditsMicro) {}

  /**
   * @param result the engine's answer
   * @return its wire form
   */
  public static TurnResponse from(AgentTurnResult result) {
    return new TurnResponse(
        result.text(),
        result.calls().stream()
            .map(c -> new CallDto(c.id(), c.name(), parse(c.argumentsJson())))
            .toList(),
        new UsageDto(result.promptTokens(), result.completionTokens(), result.creditsMicro()));
  }

  /** Arguments that are not a JSON value stay a string: they are data, and the Mac refuses them. */
  private static JsonNode parse(String json) {
    try {
      return JSON.readTree(json);
    } catch (Exception malformed) {
      return JSON.valueToTree(json);
    }
  }
}
