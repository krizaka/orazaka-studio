package com.krizaka.orazaka.studioservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.krizaka.orazaka.studioservice.domain.exception.TooManyWebhookEndpointsException;
import com.krizaka.orazaka.studioservice.domain.exception.WebhookEndpointNotFoundException;
import com.krizaka.orazaka.studioservice.domain.model.WebhookDeliveryView;
import com.krizaka.orazaka.studioservice.domain.model.WebhookEndpoint;
import com.krizaka.orazaka.studioservice.domain.port.WebhookEndpointRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A client manages its webhook endpoints; secrets are made here and shown once (orazaka#189). */
class WebhookEndpointServiceTest {

  private final List<WebhookEndpoint> stored = new ArrayList<>();
  private final List<String> secrets = new ArrayList<>();
  private Duration lastGrace;
  private StudioRuntimeConfigService config;

  private final WebhookEndpointRepository repository =
      new WebhookEndpointRepository() {
        @Override
        public int count(String actorId) {
          return stored.size();
        }

        @Override
        public WebhookEndpoint create(
            String actorId, String url, List<String> events, String secret) {
          WebhookEndpoint endpoint =
              new WebhookEndpoint(UUID.randomUUID(), url, events, true, Instant.EPOCH);
          stored.add(endpoint);
          secrets.add(secret);
          return endpoint;
        }

        @Override
        public List<WebhookEndpoint> list(String actorId) {
          return stored;
        }

        @Override
        public boolean delete(String actorId, UUID id) {
          return stored.removeIf(e -> e.id().equals(id));
        }

        @Override
        public boolean rotate(String actorId, UUID id, String secret, Duration grace) {
          lastGrace = grace;
          secrets.add(secret);
          return stored.stream().anyMatch(e -> e.id().equals(id));
        }

        @Override
        public List<WebhookDeliveryView> deliveries(String actorId, UUID id, int limit) {
          return List.of();
        }

        @Override
        public Optional<WebhookEndpoint> find(String actorId, UUID id) {
          return stored.stream().filter(e -> e.id().equals(id)).findFirst();
        }
      };

  @BeforeEach
  void config() {
    config = mock(StudioRuntimeConfigService.class);
    when(config.intValue(anyString(), anyInt())).thenAnswer(call -> call.getArgument(1));
  }

  private WebhookEndpointService service() {
    return new WebhookEndpointService(repository, config);
  }

  @Test
  void createsAnEndpointWithTheDefaultEventsAndAFreshSecretShownOnce() {
    var created = service().create("a1", " https://hooks.example.com/orazaka ", null);

    assertThat(created.endpoint().url()).isEqualTo("https://hooks.example.com/orazaka");
    assertThat(created.endpoint().events()).containsExactly("run.completed", "run.failed");
    assertThat(created.secret()).startsWith("whsec_").hasSizeGreaterThan(30);
    assertThat(secrets).containsExactly(created.secret());
    assertThat(service().create("a1", "https://hooks.example.com/b", null).secret())
        .isNotEqualTo(created.secret());
  }

  @Test
  void refusesAnAddressThatIsNotHttps() {
    for (String url :
        new String[] {
          null,
          "",
          "http://hooks.example.com",
          "ftp://x.example",
          "https://u:p@h.example",
          "not a url",
          "https:///x"
        }) {
      assertThatThrownBy(() -> service().create("a1", url, null))
          .as(String.valueOf(url))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void refusesAnUnknownEventAndKeepsTheOnesItKnows() {
    assertThatThrownBy(
            () -> service().create("a1", "https://hooks.example.com/x", List.of("run.exploded")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("run.exploded");
    assertThat(
            service()
                .create(
                    "a1", "https://hooks.example.com/x", List.of("run.cancelled", "run.cancelled"))
                .endpoint()
                .events())
        .containsExactly("run.cancelled");
  }

  @Test
  void anActorHoldsAtMostTheRowsWorthOfEndpoints() {
    when(config.intValue("webhook.max-endpoints-per-actor", 5)).thenReturn(1);
    service().create("a1", "https://hooks.example.com/1", null);

    assertThatThrownBy(() -> service().create("a1", "https://hooks.example.com/2", null))
        .isInstanceOf(TooManyWebhookEndpointsException.class);
  }

  @Test
  void rotatingIssuesANewSecretAndKeepsTheOldOneForTheGraceRow() {
    var created = service().create("a1", "https://hooks.example.com/x", null);

    String next = service().rotate("a1", created.endpoint().id());

    assertThat(next).startsWith("whsec_").isNotEqualTo(created.secret());
    assertThat(lastGrace).isEqualTo(Duration.ofHours(24));
  }

  @Test
  void whatTheActorDoesNotOwnIsNotFound() {
    UUID stranger = UUID.randomUUID();

    assertThatThrownBy(() -> service().delete("a1", stranger))
        .isInstanceOf(WebhookEndpointNotFoundException.class);
    assertThatThrownBy(() -> service().rotate("a1", stranger))
        .isInstanceOf(WebhookEndpointNotFoundException.class);
    assertThatThrownBy(() -> service().deliveries("a1", stranger, 10))
        .isInstanceOf(WebhookEndpointNotFoundException.class);
  }
}
