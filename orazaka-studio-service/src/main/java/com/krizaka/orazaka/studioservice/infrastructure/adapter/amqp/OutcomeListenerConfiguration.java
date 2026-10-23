package com.krizaka.orazaka.studioservice.infrastructure.adapter.amqp;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OutcomeListenerConfiguration {

  @Bean(destroyMethod = "close")
  public ExecutorService studioOutcomeExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  @Bean
  public SimpleRabbitListenerContainerFactory studioOutcomeListenerContainerFactory(
      SimpleRabbitListenerContainerFactoryConfigurer configurer,
      ConnectionFactory connectionFactory,
      @Qualifier("studioOutcomeExecutor") ExecutorService executor) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    configurer.configure(factory, connectionFactory);
    factory.setTaskExecutor(executor);
    return factory;
  }
}
