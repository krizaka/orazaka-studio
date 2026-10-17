package com.krizaka.orazaka.studioservice.domain.model;

/**
 * One model a form may offer for a step, as the job plane (the owner of the model catalogue)
 * reports it.
 *
 * @param value the id the run carries ({@code orazaka_models.model_name})
 * @param label the name a person reads
 * @param provider the provider that serves it
 * @param runsOn {@code local} or {@code cloud}
 * @param speed {@code fast}, {@code balanced}, {@code slow}, or null while unmeasured
 * @param typicalSeconds measured seconds for one run, null while unmeasured
 * @param priceCents US cents for one run, null when free
 * @param hint the catalogue's one-sentence description, may be null
 * @param available whether the engine answers right now
 * @param unavailableReason why not, null when available
 * @param isDefault whether this is the model used when the person chooses nothing
 */
public record ModelChoice(
    String value,
    String label,
    String provider,
    String runsOn,
    String speed,
    Integer typicalSeconds,
    Integer priceCents,
    String hint,
    boolean available,
    String unavailableReason,
    boolean isDefault) {}
