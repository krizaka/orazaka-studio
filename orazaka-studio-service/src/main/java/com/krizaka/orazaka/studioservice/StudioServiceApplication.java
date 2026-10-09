package com.krizaka.orazaka.studioservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Studio service (ADR-034) — the marketplace of installable business workflows.
 *
 * <p>Owns the catalogue, the versioned blueprints, each actor's installation, and the run sagas
 * those installations produce. A Studio is <b>data</b>: publishing one is an admin action, never a
 * deploy.
 *
 * <p>A separate process from the conversation service on purpose: its write pattern is hour-long
 * sagas, which has no business sharing a connection pool or a virtual-thread budget with sub-second
 * interactive chat.
 *
 * <p>{@code @EnableScheduling} is not decoration: the run sweeper is what stops a crashed worker
 * leaving a hold outstanding and freezing a paying actor's balance.
 */
@SpringBootApplication
@EnableScheduling
public class StudioServiceApplication {

  /**
   * Boots the studio service.
   *
   * @param args standard Spring Boot command-line arguments
   */
  public static void main(String[] args) {
    SpringApplication.run(StudioServiceApplication.class, args);
  }
}
