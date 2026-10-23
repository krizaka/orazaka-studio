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

  @Scheduled(fixedDelay = 5000)
  void refreshConcurrency() {
    if (!(registry.getListenerContainer(LISTENER_ID)
        instanceof SimpleMessageListenerContainer container)) {
      return;
    }
    int configured =
        Math.max(1, runtimeConfigService.intValue(CONCURRENCY_KEY, CONCURRENCY_FALLBACK));
    int current = container.getConcurrentConsumers();
    int maximum = container.getMaxConcurrentConsumers();
    boolean maximumRaised = configured > maximum;
    if (maximumRaised) {
      container.setMaxConcurrentConsumers(configured);
    }
    if (configured != current) {
      container.setConcurrentConsumers(configured);
    }
    if (!maximumRaised && configured != maximum) {
      container.setMaxConcurrentConsumers(configured);
    }
  }
}
