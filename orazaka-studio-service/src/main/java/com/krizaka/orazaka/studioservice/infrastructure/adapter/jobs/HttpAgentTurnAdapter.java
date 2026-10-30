package com.krizaka.orazaka.studioservice.infrastructure.adapter.jobs;

import com.krizaka.orazaka.studioservice.domain.exception.AgentTurnRefusedException;
import com.krizaka.orazaka.studioservice.domain.model.AgentTurn;
import com.krizaka.orazaka.studioservice.domain.model.AgentTurnResult;
import com.krizaka.orazaka.studioservice.domain.port.AgentTurnClient;
import com.krizaka.orazaka.studioservice.infrastructure.config.ConversationPlaneProperties;
import com.krizaka.security.jwt.SessionJwtProperties;
import com.krizaka.security.token.ServiceTokenProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * The model turn, run by the AI engine hosted in the conversation service: {@code POST
 * /internal/v1/agent-turns}, called as a service, the actor passed in the body. The host applies
 * the routing rows of the feature key, the per-tool roles of the actor and the metering; the
 * engine's refusals come back as {@code status} in the body (the server's own words) and are passed
 * on unchanged.
 */
@Component
class HttpAgentTurnAdapter implements AgentTurnClient {

  private static final Logger logger = LoggerFactory.getLogger(HttpAgentTurnAdapter.class);

  private static final String PATH = "/internal/v1/agent-turns";
  private static final String UNAVAILABLE = "engine_unavailable";

  private final RestClient engineClient;

  HttpAgentTurnAdapter(
      RestClient.Builder restClientBuilder,
      ConversationPlaneProperties properties,
      SessionJwtProperties sessionJwt) {
    ServiceTokenProvider tokens =
        new ServiceTokenProvider(sessionJwt.secret(), "orazaka-studio-service");
    this.engineClient =
        restClientBuilder
            .baseUrl(properties.baseUrl())
            .requestInitializer(request -> request.getHeaders().setBearerAuth(tokens.token()))
            .build();
  }

  @Override
  public AgentTurnResult turn(AgentTurn turn) {
    try {
      JsonNode answer =
          engineClient.post().uri(PATH).body(body(turn)).retrieve().body(JsonNode.class);
      return result(answer);
    } catch (RestClientResponseException refused) {
      throw new AgentTurnRefusedException(
          statusOf(refused),
          refused.getStatusCode().is4xxClientError() ? refused.getStatusCode().value() : 502);
    } catch (RuntimeException unreachable) {
      logger.warn("Engine turn failed for run {}: {}", turn.runId(), unreachable.toString());
      throw new AgentTurnRefusedException(UNAVAILABLE, 503);
    }
  }

  private static Map<String, Object> body(AgentTurn turn) {
    return Map.of(
        "runId", turn.runId().toString(),
        "actorId", turn.actorId(),
        "featureKey", turn.featureKey(),
        "system", turn.system(),
        "messages", turn.messages(),
        "tools", turn.tools());
  }

  private static AgentTurnResult result(JsonNode answer) {
    if (answer == null || !answer.isObject()) {
      throw new AgentTurnRefusedException(UNAVAILABLE, 502);
    }
    List<AgentTurn.Call> calls = toCalls(answer.path("toolCalls"));
    JsonNode usage = answer.path("usage");
    return new AgentTurnResult(
        answer.path("text").asText(""),
        calls,
        usage.path("promptTokens").asLong(0),
        usage.path("completionTokens").asLong(0),
        usage.path("creditsMicro").asLong(0));
  }

  private static List<AgentTurn.Call> toCalls(JsonNode array) {
    List<AgentTurn.Call> calls = new ArrayList<>();
    for (JsonNode call : array) {
      calls.add(
          new AgentTurn.Call(
              call.path("id").asText(""),
              call.path("name").asText(),
              call.path("argumentsJson").asText("{}")));
    }
    return calls;
  }

  /** The host's own word for the refusal ({@code RestErrorResolver} body {@code status}). */
  private static String statusOf(RestClientResponseException refused) {
    try {
      JsonNode body = refused.getResponseBodyAs(JsonNode.class);
      String status = body == null ? "" : body.path("status").asText("");
      return status.isBlank() ? UNAVAILABLE : status;
    } catch (RuntimeException unreadable) {
      return UNAVAILABLE;
    }
  }
}
