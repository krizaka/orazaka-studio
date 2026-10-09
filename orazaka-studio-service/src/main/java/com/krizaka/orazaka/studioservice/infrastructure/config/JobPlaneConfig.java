package com.krizaka.orazaka.studioservice.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring of the job plane this service dispatches onto.
 *
 * <p>Only the collaborator's address and staleness bound live here. What runs where is {@code
 * orazaka_capabilities}, read through {@code CapabilityRoutingClient} — a routing key in this
 * service's configuration would be a second copy of a table that has one owner (ADR-037).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CapabilityRoutingProperties.class)
public class JobPlaneConfig {}
