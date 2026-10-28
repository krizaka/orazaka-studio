package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.orazaka.studioservice.domain.model.AgentTurn;
import com.krizaka.orazaka.studioservice.domain.model.AgentTurnResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The wire the Mac app speaks (apps/desktop {@code code::agent}): read as it sends it, answered as
 * it reads.
 */
class TurnWireTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  @DisplayName("a turn is read as the Mac sends it, tool_call_id and the tool's effect included")
  void readsTheMacsTurn() throws Exception {
    String body =
        """
        {"featureKey":"anything-the-client-says","system":"fixed",
         "messages":[{"role":"user","content":"fix it"},
           {"role":"assistant","content":"","calls":[{"id":"c1","name":"read_file","args":{"path":"a.rs"}}]},
           {"role":"tool","content":"<untrusted>x</untrusted>","tool_call_id":"c1"}],
         "tools":[{"name":"read_file","effect":"read","description":"Read","parameters":{"type":"object"}}]}
        """;
    TurnRequest request = JSON.readValue(body, TurnRequest.class);

    List<AgentTurn.Message> messages = request.domainMessages();
    assertThat(messages.get(1).calls())
        .extracting(AgentTurn.Call::argumentsJson)
        .containsExactly("{\"path\":\"a.rs\"}");
    assertThat(messages.get(2).toolCallId()).isEqualTo("c1");
    assertThat(request.domainTools())
        .extracting(AgentTurn.Tool::inputSchema)
        .containsExactly("{\"type\":\"object\"}");
  }

  @Test
  @DisplayName(
      "the answer carries text, calls with object arguments and the usage, as the Mac reads them")
  void answersAsTheMacReads() {
    TurnResponse response =
        TurnResponse.from(
            new AgentTurnResult(
                "",
                List.of(new AgentTurn.Call("c2", "list_dir", "{\"path\":\"src\"}")),
                12,
                3,
                40));

    JsonNode json = JSON.valueToTree(response);
    assertThat(json.path("calls").get(0).path("args").path("path").asText()).isEqualTo("src");
    assertThat(json.path("calls").get(0).path("name").asText()).isEqualTo("list_dir");
    assertThat(json.path("usage").path("promptTokens").asLong()).isEqualTo(12);
    assertThat(json.path("usage").path("creditsMicro").asLong()).isEqualTo(40);
  }
}
