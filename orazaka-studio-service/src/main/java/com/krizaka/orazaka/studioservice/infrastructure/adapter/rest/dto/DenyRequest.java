package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

/**
 * The body of {@code POST /runs/{id}/deny} (orazaka-studio#115).
 *
 * @param reason why the approval is refused; optional
 */
public record DenyRequest(String reason) {}
