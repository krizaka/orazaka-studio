package com.orazaka.studioservice.infrastructure.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the studio service's saga queue and its dead-letter path.
 *
 * <p>The exchanges are declared durably by every participant — RabbitMQ treats identical
 * declarations as idempotent, and a service that assumed someone else had declared them could not
 * boot first.
 *
 * <p>The DLQ is not optional decoration: a job outcome that cannot be applied must be inspectable
 * rather than lost, because a lost outcome leaves a run non-terminal and its hold outstanding —
 * exactly the failure ADR-034 names as the one to guard.
 */
@Configuration
class AmqpConfiguration {

  @Bean
  TopicExchange studioJobsExchange() {
    return new TopicExchange(AmqpConstants.JOBS_EXCHANGE, true, false);
  }

  @Bean
  TopicExchange studioEventsExchange() {
    return new TopicExchange(AmqpConstants.EVENTS_EXCHANGE, true, false);
  }

  @Bean
  DirectExchange studioDeadLetterExchange() {
    return new DirectExchange(AmqpConstants.DLX_EXCHANGE, true, false);
  }

  @Bean
  Queue studioSagaQueue() {
    return QueueBuilder.durable(AmqpConstants.SAGA_QUEUE)
        .deadLetterExchange(AmqpConstants.DLX_EXCHANGE)
        .deadLetterRoutingKey(AmqpConstants.SAGA_DLQ)
        .build();
  }

  @Bean
  Queue studioSagaDlq() {
    return QueueBuilder.durable(AmqpConstants.SAGA_DLQ).build();
  }

  @Bean
  Binding studioSagaDoneBinding(Queue studioSagaQueue, TopicExchange studioEventsExchange) {
    return BindingBuilder.bind(studioSagaQueue)
        .to(studioEventsExchange)
        .with(AmqpConstants.DONE_BINDING);
  }

  @Bean
  Binding studioSagaErrorBinding(Queue studioSagaQueue, TopicExchange studioEventsExchange) {
    return BindingBuilder.bind(studioSagaQueue)
        .to(studioEventsExchange)
        .with(AmqpConstants.ERROR_BINDING);
  }

  @Bean
  Binding studioSagaDlqBinding(Queue studioSagaDlq, DirectExchange studioDeadLetterExchange) {
    return BindingBuilder.bind(studioSagaDlq)
        .to(studioDeadLetterExchange)
        .with(AmqpConstants.SAGA_DLQ);
  }

  @Bean
  Queue studioConnectorQueue() {
    return QueueBuilder.durable(AmqpConstants.CONNECTOR_QUEUE)
        .deadLetterExchange(AmqpConstants.DLX_EXCHANGE)
        .deadLetterRoutingKey(AmqpConstants.CONNECTOR_DLQ)
        .build();
  }

  @Bean
  Queue studioConnectorDlq() {
    return QueueBuilder.durable(AmqpConstants.CONNECTOR_DLQ).build();
  }

  @Bean
  Binding studioConnectorBinding(Queue studioConnectorQueue, TopicExchange studioEventsExchange) {
    return BindingBuilder.bind(studioConnectorQueue)
        .to(studioEventsExchange)
        .with(AmqpConstants.CONNECTOR_BINDING);
  }

  @Bean
  Binding studioConnectorDlqBinding(
      Queue studioConnectorDlq, DirectExchange studioDeadLetterExchange) {
    return BindingBuilder.bind(studioConnectorDlq)
        .to(studioDeadLetterExchange)
        .with(AmqpConstants.CONNECTOR_DLQ);
  }

  @Bean
  Queue studioCapabilityQueue() {
    return QueueBuilder.durable(AmqpConstants.CAPABILITY_QUEUE)
        .deadLetterExchange(AmqpConstants.DLX_EXCHANGE)
        .deadLetterRoutingKey(AmqpConstants.CAPABILITY_DLQ)
        .build();
  }

  @Bean
  Queue studioCapabilityDlq() {
    return QueueBuilder.durable(AmqpConstants.CAPABILITY_DLQ).build();
  }

  @Bean
  Binding studioCapabilityBinding(Queue studioCapabilityQueue, TopicExchange studioEventsExchange) {
    return BindingBuilder.bind(studioCapabilityQueue)
        .to(studioEventsExchange)
        .with(AmqpConstants.CAPABILITY_BINDING);
  }

  @Bean
  Binding studioCapabilityDlqBinding(
      Queue studioCapabilityDlq, DirectExchange studioDeadLetterExchange) {
    return BindingBuilder.bind(studioCapabilityDlq)
        .to(studioDeadLetterExchange)
        .with(AmqpConstants.CAPABILITY_DLQ);
  }

  @Bean
  Queue studioSubscriptionQueue() {
    return QueueBuilder.durable(AmqpConstants.SUBSCRIPTION_QUEUE)
        .deadLetterExchange(AmqpConstants.DLX_EXCHANGE)
        .deadLetterRoutingKey(AmqpConstants.SUBSCRIPTION_DLQ)
        .build();
  }

  @Bean
  Queue studioSubscriptionDlq() {
    return QueueBuilder.durable(AmqpConstants.SUBSCRIPTION_DLQ).build();
  }

  @Bean
  Binding studioSubscriptionBinding(
      Queue studioSubscriptionQueue, TopicExchange studioEventsExchange) {
    return BindingBuilder.bind(studioSubscriptionQueue)
        .to(studioEventsExchange)
        .with(AmqpConstants.SUBSCRIPTION_BINDING);
  }

  @Bean
  Binding studioSubscriptionDlqBinding(
      Queue studioSubscriptionDlq, DirectExchange studioDeadLetterExchange) {
    return BindingBuilder.bind(studioSubscriptionDlq)
        .to(studioDeadLetterExchange)
        .with(AmqpConstants.SUBSCRIPTION_DLQ);
  }

  /**
   * JSON on the wire, like every other service on these exchanges.
   *
   * <p>Not optional: without this bean Spring AMQP falls back to Java serialization, and this
   * service would publish commands the job service cannot read while failing to deserialize the
   * outcomes it must consume — a mismatch that shows up as silence, not as an error.
   */
  @Bean
  MessageConverter jsonMessageConverter() {
    return new JacksonJsonMessageConverter();
  }
}
