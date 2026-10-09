package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

/**
 * What an install applied.
 *
 * @param packKey the bundle installed
 * @param version the bundle's version
 * @param studiosInstalled how many Studios it wrote
 */
public record PackInstallResponse(String packKey, String version, int studiosInstalled) {}
