package com.krizaka.orazaka.studioservice.infrastructure.adapter.amqp;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.krizaka.messaging.dedup.MessageDedup;
import com.krizaka.orazaka.studioservice.application.service.InstallationLifecycleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SubscriptionChangeListenerTest {

  private static final String ACTOR = "550e8400-e29b-41d4-a716-446655440002";

  private final InstallationLifecycleService lifecycleService =
      mock(InstallationLifecycleService.class);
  private final MessageDedup dedupService = mock(MessageDedup.class);
  private final SubscriptionChangeListener listener =
      new SubscriptionChangeListener(lifecycleService, dedupService);

  private void claims() {
    when(dedupService.claim(anyString(), anyString())).thenReturn(true);
  }

  @Test
  @DisplayName("A lapsed subscription pauses the workspace instead of failing each run one by one")
  void lapsePauses() {
    claims();

    listener.onSubscriptionChanged(
        new SubscriptionChangeListener.SubscriptionEvent(ACTOR, "premium", "PAST_DUE"), "m1");

    verify(lifecycleService).pauseFor(ACTOR);
  }

  @Test
  @DisplayName("An active or trialing subscription keeps the workspace alive")
  void activeResumes() {
    claims();

    listener.onSubscriptionChanged(
        new SubscriptionChangeListener.SubscriptionEvent(ACTOR, "premium", "ACTIVE"), "m2");
    listener.onSubscriptionChanged(
        new SubscriptionChangeListener.SubscriptionEvent(ACTOR, "premium", "TRIALING"), "m3");

    verify(lifecycleService, org.mockito.Mockito.times(2)).resumeFor(ACTOR);
    verify(lifecycleService, never()).pauseFor(anyString());
  }

  @Test
  @DisplayName(
      "A cancelled subscription pauses, never deletes — a lapse is not a decision to leave")
  void cancelledPauses() {
    claims();

    listener.onSubscriptionChanged(
        new SubscriptionChangeListener.SubscriptionEvent(ACTOR, "free", "CANCELED"), "m4");

    verify(lifecycleService).pauseFor(ACTOR);
  }

  @Test
  @DisplayName(
      "A malformed announcement changes nothing — guessing would be a self-inflicted outage")
  void malformedEventIsIgnored() {
    listener.onSubscriptionChanged(null, "m5");
    listener.onSubscriptionChanged(
        new SubscriptionChangeListener.SubscriptionEvent(null, "premium", "ACTIVE"), "m6");
    listener.onSubscriptionChanged(
        new SubscriptionChangeListener.SubscriptionEvent(ACTOR, "premium", null), "m7");

    verify(lifecycleService, never()).pauseFor(anyString());
    verify(lifecycleService, never()).resumeFor(anyString());
  }

  @Test
  @DisplayName("A redelivery is dropped by the dedup ledger")
  void redeliveryIsDropped() {
    when(dedupService.claim(anyString(), anyString())).thenReturn(false);

    listener.onSubscriptionChanged(
        new SubscriptionChangeListener.SubscriptionEvent(ACTOR, "premium", "PAST_DUE"), "m8");

    verify(lifecycleService, never()).pauseFor(anyString());
  }
}
