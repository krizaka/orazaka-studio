package com.krizaka.orazaka.studioservice.infrastructure.adapter.jobs;

import com.krizaka.orazaka.studioservice.domain.exception.ErasureIncompleteException;
import com.krizaka.orazaka.studioservice.domain.port.ConversationErasureClient;
import com.krizaka.orazaka.studioservice.infrastructure.config.ConversationPlaneProperties;
import com.krizaka.security.jwt.SessionJwtProperties;
import com.krizaka.security.token.ServiceTokenProvider;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Erases conversations through the conversation service's machine surface ({@code POST
 * /internal/v1/protected-conversations/erase}).
 *
 * <p>Like the job-plane adapter, a failure is <b>never</b> degraded to "nothing to do": an erasure
 * that skipped the conversation memory would tell a person their words are gone while the memory of
 * their listening turns still holds them.
 */
@Component
class HttpConversationErasureAdapter implements ConversationErasureClient {

  private static final String ERASE_PATH = "/internal/v1/protected-conversations/erase";

  private final RestClient conversationClient;

  HttpConversationErasureAdapter(
      RestClient.Builder restClientBuilder,
      ConversationPlaneProperties properties,
      SessionJwtProperties sessionJwt) {
    Objects.requireNonNull(properties, "ConversationPlaneProperties cannot be null");
    ServiceTokenProvider tokens =
        new ServiceTokenProvider(sessionJwt.secret(), "orazaka-studio-service");
    this.conversationClient =
        restClientBuilder
            .baseUrl(properties.baseUrl())
            .requestInitializer(request -> request.getHeaders().setBearerAuth(tokens.token()))
            .build();
  }

  @Override
  public int erase(String actorId, String packKey) {
    try {
      Map<String, Object> answer =
          conversationClient
              .post()
              .uri(ERASE_PATH)
              .body(Map.of("actorId", actorId, "packKey", packKey))
              .retrieve()
              .body(new ParameterizedTypeReference<Map<String, Object>>() {});
      Object erased = answer == null ? null : answer.get("conversations");
      return erased instanceof Number number ? number.intValue() : 0;
    } catch (RestClientException e) {
      throw new ErasureIncompleteException(
          "the conversation service could not erase the memory of these turns; nothing was"
              + " deleted",
          e);
    }
  }
}
