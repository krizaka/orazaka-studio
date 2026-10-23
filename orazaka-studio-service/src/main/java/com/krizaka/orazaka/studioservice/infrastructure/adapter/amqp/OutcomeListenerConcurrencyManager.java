package com.krizaka.orazaka.studioservice.infrastructure.adapter.amqp;

import com.krizaka.orazaka.studioservice.application.service.StudioRuntimeConfigService;
import java.util.Objects;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class OutcomeListenerConcurrencyManager {

  private static final String CONCURRENCY_KEY = "run.outcome-listener.concurrency";
  private static final int CONCURRENCY_FALLBACK = 2;
  private static final String LISTENER_ID = "studio-job-outcome";

  private final RabbitListenerEndpointRegistry registry;
  private final StudioRuntimeConfigService runtimeConfigService;

  OutcomeListenerConcurrencyManager(
      RabbitListenerEndpointRegistry registry, StudioRuntimeConfigService runtimeConfigService) {
    this.registry = Objects.requireNonNull(registry, "RabbitListenerEndpointRegistry required");
    this.runtimeConfigService =
        Objects.requireNonNull(runtimeConfigService, "StudioRuntimeConfigService required");
  }

  /**
   * The consumer count last applied; the container exposes setters only, so this is the one source
   * of it.
   */
  private volatile int applied = -1;

  @Scheduled(fixedDelay = 5000)
  void refreshConcurrency() {
    if (!(registry.getListenerContainer(LISTENER_ID)
        instanceof SimpleMessageListenerContainer container)) {
      return;
    }
    int configured =
        Math.max(1, runtimeConfigService.intValue(CONCURRENCY_KEY, CONCURRENCY_FALLBACK));
    if (configured == applied) {
      return;
    }
    // The count may never exceed the maximum: raise the maximum first when growing, lower the count
    // first when shrinking.
    if (configured > applied) {
      container.setMaxConcurrentConsumers(configured);
      container.setConcurrentConsumers(configured);
    } else {
      container.setConcurrentConsumers(configured);
      container.setMaxConcurrentConsumers(configured);
    }
    applied = configured;
  }
}
