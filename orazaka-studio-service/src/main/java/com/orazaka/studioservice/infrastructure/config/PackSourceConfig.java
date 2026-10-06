package com.orazaka.studioservice.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds where this deployment's packs come from (ADR-049, ADR-068).
 *
 * <p>A separate {@code @Configuration} for the reason {@code AssetStoreConfig} is one: what it
 * binds decides what a fresh environment ends up with — a deployment that declares no source ships
 * no Studios — and a reader looking for that decision should find a file named after it.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PackSourceProperties.class)
public class PackSourceConfig {}
