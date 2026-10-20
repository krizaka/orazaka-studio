package com.krizaka.orazaka.studioservice.infrastructure.adapter.schedule;

import com.krizaka.orazaka.studioservice.application.service.WebhookDispatchService;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Queues and sends the webhooks of finished runs (orazaka#189 slice B). */
@Component
public class WebhookSweeper {

  private static final Logger logger = LoggerFactory.getLogger(WebhookSweeper.class);

  private final WebhookDispatchService dispatch;

  public WebhookSweeper(WebhookDispatchService dispatch) {
    this.dispatch = Objects.requireNonNull(dispatch, "WebhookDispatchService cannot be null");
  }

  /** One sweep; a failure is logged and the next sweep tries again. */
  @Scheduled(fixedDelayString = "${orazaka.studio-service.webhooks.interval:10000}")
  public void sweep() {
    try {
      int attempted = dispatch.sweep();
      if (attempted > 0) {
        logger.info("webhooks: {} deliveries attempted", attempted);
      }
    } catch (RuntimeException failure) {
      logger.warn("webhook sweep failed: {}", failure.toString());
    }
  }
}
