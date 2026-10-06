package com.orazaka.studioservice.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds what this deployment claims about its asset store (ADR-051 §8).
 *
 * <p>A separate {@code @Configuration} rather than a line on an existing one: the claim it binds
 * decides whether a protected pack may be installed at all, and a reader looking for that decision
 * should find a file named after it.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AssetStoreProperties.class)
public class AssetStoreConfig {}
