package com.krizaka.orazaka.studioservice.infrastructure.adapter.amqp;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.krizaka.orazaka.studioservice.application.service.StudioRuntimeConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

class OutcomeListenerConcurrencyManagerTest {

  @Test
  @DisplayName("Updates outcome consumers from the live database setting")
  void adjustsConsumerCountFromRuntimeConfiguration() {
    RabbitListenerEndpointRegistry registry = mock(RabbitListenerEndpointRegistry.class);
    SimpleMessageListenerContainer container = mock(SimpleMessageListenerContainer.class);
    StudioRuntimeConfigService config = mock(StudioRuntimeConfigService.class);
    when(registry.getListenerContainer("studio-job-outcome")).thenReturn(container);
    when(config.intValue("run.outcome-listener.concurrency", 2)).thenReturn(4);
    when(container.getConcurrentConsumers()).thenReturn(1);
    when(container.getMaxConcurrentConsumers()).thenReturn(1);

    new OutcomeListenerConcurrencyManager(registry, config).refreshConcurrency();

    verify(container).setMaxConcurrentConsumers(4);
    verify(container).setConcurrentConsumers(4);
  }
}
