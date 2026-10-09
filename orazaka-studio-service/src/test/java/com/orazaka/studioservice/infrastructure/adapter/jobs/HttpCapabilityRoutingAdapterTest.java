package com.orazaka.studioservice.infrastructure.adapter.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.security.jwt.SessionJwtProperties;
import com.orazaka.jobs.domain.model.CapabilityRoute;
import com.orazaka.studioservice.infrastructure.config.CapabilityRoutingProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Behavioural round-trip against a JDK stub of the job service's routing surface: the mapping is
 * read, an absent route stays absent, and the hot path does not re-read what it just resolved.
 */
class HttpCapabilityRoutingAdapterTest {

  private static HttpServer stub;
  private static final AtomicInteger routedHits = new AtomicInteger();

  @BeforeAll
  static void startStub() throws IOException {
    stub = HttpServer.create(new InetSocketAddress(0), 0);
    stub.createContext(
        "/internal/v1/capabilities/orazaka.core.media.vision/route",
        exchange -> {
          routedHits.incrementAndGet();
          respond(
              exchange,
              200,
              "{\"featureKey\":\"orazaka.core.media.vision\",\"routingKey\":\"job.media.generate\","
                  + "\"billableUnit\":\"IMAGE_STEP\",\"enabled\":true}");
        });
    stub.createContext(
        "/internal/v1/capabilities/orazaka.doc.validate/route",
        exchange -> respond(exchange, 404, "no enabled route"));
    stub.start();
  }

  @AfterAll
  static void stopStub() {
    stub.stop(0);
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  @BeforeEach
  void resetHits() {
    routedHits.set(0);
  }

  private HttpCapabilityRoutingAdapter adapter(Duration ttl) {
    return new HttpCapabilityRoutingAdapter(
        RestClient.builder(),
        new CapabilityRoutingProperties("http://127.0.0.1:" + stub.getAddress().getPort(), ttl),
        new SessionJwtProperties("orazaka-test-secret-at-least-32-characters!"));
  }

  @Test
  @DisplayName("Resolves the route the registry carries, whole")
  void resolvesTheRoute() {
    Optional<CapabilityRoute> route =
        adapter(Duration.ofMinutes(1)).route("orazaka.core.media.vision");

    assertThat(route).isPresent();
    assertThat(route.get().routingKey()).isEqualTo("job.media.generate");
    assertThat(route.get().billableUnit()).isEqualTo("IMAGE_STEP");
  }

  @Test
  @DisplayName("Caches to its TTL — a fan-out over 40 photos is not 40 lookups")
  void cachesWithinTheTtl() {
    HttpCapabilityRoutingAdapter adapter = adapter(Duration.ofMinutes(1));

    adapter.route("orazaka.core.media.vision");
    adapter.route("orazaka.core.media.vision");

    assertThat(routedHits.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("A capability with no enabled route resolves to empty, never to a default")
  void anUnroutableCapabilityIsEmpty() {
    assertThat(adapter(Duration.ofMinutes(1)).route("orazaka.doc.validate")).isEmpty();
  }

  @Test
  @DisplayName("An unreachable job service refuses the dispatch — it does not guess a queue")
  void anUnreachableJobPlaneRefusesRatherThanGuesses() {
    HttpCapabilityRoutingAdapter offline =
        new HttpCapabilityRoutingAdapter(
            RestClient.builder(),
            // Port 1 is reserved and unbound: the connection fails rather than hanging.
            new CapabilityRoutingProperties("http://127.0.0.1:1", Duration.ofMinutes(1)),
            new SessionJwtProperties("orazaka-test-secret-at-least-32-characters!"));

    assertThat(offline.route("orazaka.core.media.vision")).isEmpty();
  }

  @Test
  @DisplayName("A 404 is an answer and is cached; an outage is not, and is retried")
  void anOutageIsNotCachedLikeAnAnswer() {
    // The 404 path: authoritative, so the second call is served from the cache.
    HttpCapabilityRoutingAdapter adapter = adapter(Duration.ofMinutes(1));
    assertThat(adapter.route("orazaka.doc.validate")).isEmpty();
    assertThat(adapter.route("orazaka.doc.validate")).isEmpty();

    // The outage path: one dropped connection must not refuse every step for a whole TTL, so the
    // failure is remembered for seconds, not for the configured minute.
    HttpCapabilityRoutingAdapter offline =
        new HttpCapabilityRoutingAdapter(
            RestClient.builder(),
            new CapabilityRoutingProperties("http://127.0.0.1:1", Duration.ofHours(1)),
            new SessionJwtProperties("orazaka-test-secret-at-least-32-characters!"));
    assertThat(offline.route("orazaka.core.media.vision")).isEmpty();
    assertThat(offline.route("orazaka.core.media.vision")).isEmpty();
  }

  @Test
  @DisplayName("A blank capability never reaches the network")
  void aBlankCapabilityIsEmpty() {
    assertThat(adapter(Duration.ofMinutes(1)).route("  ")).isEmpty();
    assertThat(routedHits.get()).isZero();
  }
}
