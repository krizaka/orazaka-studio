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
    OutcomeListenerConcurrencyManager manager =
        new OutcomeListenerConcurrencyManager(registry, config);

    manager.refreshConcurrency();
    verify(container).setMaxConcurrentConsumers(4);
    verify(container).setConcurrentConsumers(4);

    // The same value again changes nothing; a lower one lowers the count before the maximum.
    manager.refreshConcurrency();
    when(config.intValue("run.outcome-listener.concurrency", 2)).thenReturn(1);
    manager.refreshConcurrency();
    org.mockito.InOrder order = org.mockito.Mockito.inOrder(container);
    order.verify(container).setConcurrentConsumers(1);
    order.verify(container).setMaxConcurrentConsumers(1);
    verify(container, org.mockito.Mockito.times(1)).setConcurrentConsumers(4);
  }
}
