package com.krizaka.orazaka.studioservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.krizaka.orazaka.studioservice.domain.model.DueWebhook;
import com.krizaka.orazaka.studioservice.domain.policy.WebhookSignature;
import com.krizaka.orazaka.studioservice.domain.port.WebhookDeliveryStore;
import com.krizaka.orazaka.studioservice.domain.port.WebhookSender;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A client is told when its run is over, signed, retried with a backoff, then given up on
 * (orazaka#189).
 */
class WebhookDispatchServiceTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC);

  private final List<String> log = new ArrayList<>();
  private List<DueWebhook> due = new ArrayList<>();
  private final List<Map<String, String>> sentHeaders = new ArrayList<>();
  private final List<String> sentBodies = new ArrayList<>();
  private Object answer = 200;
  private StudioRuntimeConfigService config;

  private final WebhookDeliveryStore store =
      new WebhookDeliveryStore() {
        @Override
        public int enqueueFinishedRuns() {
          log.add("enqueue");
          return 0;
        }

        @Override
        public List<DueWebhook> claimDue(int limit, Duration lease) {
          log.add("claim " + limit + " lease=" + lease.toSeconds());
          return due;
        }

        @Override
        public void delivered(UUID id, int httpStatus) {
          log.add("delivered " + httpStatus);
        }

        @Override
        public void retryLater(UUID id, Integer httpStatus, String error, Duration retryIn) {
          log.add("retry " + httpStatus + " in " + retryIn.toSeconds() + "s: " + error);
        }

        @Override
        public void dead(UUID id, Integer httpStatus, String error) {
          log.add("dead " + httpStatus + ": " + error);
        }
      };

  private final WebhookSender sender =
      (url, headers, body, timeout) -> {
        sentHeaders.add(headers);
        sentBodies.add(body);
        if (answer instanceof IOException failure) {
          throw failure;
        }
        return (Integer) answer;
      };

  @BeforeEach
  void config() {
    config = mock(StudioRuntimeConfigService.class);
    when(config.intValue(anyString(), anyInt())).thenAnswer(call -> call.getArgument(1));
  }

  private WebhookDispatchService service() {
    return new WebhookDispatchService(store, sender, config, CLOCK);
  }

  private static DueWebhook webhook(int attempts, String previousSecret) {
    return new DueWebhook(
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        "https://hooks.example.com/orazaka",
        "run.completed",
        "{\"type\":\"run.completed\"}",
        attempts,
        "s3cret",
        previousSecret);
  }

  @Test
  void queuesWhatFinishedThenSendsWhatIsDue() {
    due = List.of(webhook(0, null));

    assertThat(service().sweep()).isEqualTo(1);

    assertThat(log).containsExactly("enqueue", "claim 20 lease=40", "delivered 200");
  }

  @Test
  void signsTheExactBodyWithTheDeliveryIdThatStaysTheSameAcrossRetries() {
    due = List.of(webhook(0, null));
    service().sweep();

    Map<String, String> headers = sentHeaders.get(0);
    assertThat(headers.get("webhook-id")).isEqualTo("msg_00000000-0000-0000-0000-000000000001");
    assertThat(headers.get("webhook-timestamp")).isEqualTo("1700000000");
    assertThat(headers.get("webhook-signature"))
        .isEqualTo(
            WebhookSignature.sign(
                "s3cret", headers.get("webhook-id"), 1_700_000_000L, sentBodies.get(0)));
  }

  @Test
  void bothSecretsSignDuringARotation() {
    due = List.of(webhook(0, "old"));
    service().sweep();

    String signature = sentHeaders.get(0).get("webhook-signature");
    assertThat(signature.split(" ")).hasSize(2);
    assertThat(signature.split(" ")[1])
        .isEqualTo(
            WebhookSignature.sign(
                "old",
                "msg_00000000-0000-0000-0000-000000000001",
                1_700_000_000L,
                sentBodies.get(0)));
  }

  @Test
  void aRefusalIsRetriedWithADoublingWaitCappedByTheRow() {
    answer = 503;
    due = List.of(webhook(0, null));
    service().sweep();
    due = List.of(webhook(1, null));
    service().sweep();
    due = List.of(webhook(2, null));
    service().sweep();

    assertThat(log)
        .contains(
            "retry 503 in 30s: the receiver answered 503",
            "retry 503 in 60s: the receiver answered 503",
            "retry 503 in 120s: the receiver answered 503");
    assertThat(service().backoff(20).toSeconds()).isEqualTo(3600);
  }

  @Test
  void anUnreachableReceiverIsRetriedToo() {
    answer = new IOException("connection refused");
    due = List.of(webhook(0, null));
    service().sweep();

    assertThat(log)
        .anyMatch(line -> line.startsWith("retry null in 30s: not delivered: connection refused"));
  }

  @Test
  void givesUpAfterTheMaxAttemptsAndKeepsTheLog() {
    answer = 500;
    due = List.of(webhook(7, null));
    service().sweep();

    assertThat(log).anyMatch(line -> line.startsWith("dead 500"));
    assertThat(log).noneMatch(line -> line.startsWith("retry"));
  }

  @Test
  void theLimitsAreRowsNotCode() {
    when(config.intValue("webhook.max-attempts", 8)).thenReturn(2);
    when(config.intValue("webhook.backoff-seconds", 30)).thenReturn(5);
    answer = 500;
    due = List.of(webhook(0, null));
    service().sweep();
    due = List.of(webhook(1, null));
    service().sweep();

    assertThat(log).anyMatch(line -> line.startsWith("retry 500 in 5s"));
    assertThat(log).anyMatch(line -> line.startsWith("dead 500"));
  }
}
