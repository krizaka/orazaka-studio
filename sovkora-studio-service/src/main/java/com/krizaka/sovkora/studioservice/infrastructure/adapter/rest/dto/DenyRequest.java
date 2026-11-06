package com.krizaka.sovkora.studioservice.infrastructure.adapter.rest.dto;

/**
 * The body of {@code POST /runs/{id}/deny} (sovkora-studio-service#115).
 *
 * @param reason why the approval is refused; optional
 */
public record DenyRequest(String reason) {}
