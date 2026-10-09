package com.krizaka.orazaka.studioservice.domain.model;

/**
 * What a caller supplies at install time for a {@code REGULATED} pack.
 *
 * <p>Three separate declarations, because they answer three different questions and a single "I
 * agree" would collapse them: what statement was agreed to, whether the person is of age, and which
 * region's crisis line should answer them.
 *
 * @param consentVersion the version of the statement the user agreed to; must match what the pack
 *     currently requires, or the install is refused and the new statement is returned
 * @param ageAttested whether the user attested to being of age. Identity holds no date of birth
 *     (ADR-055 §6), so this is a self-declaration recorded on the installation — weak evidence, and
 *     the only kind available without collecting a birth date from every user of the platform
 * @param region which region's verified crisis resource answers a crisis turn; the pack is
 *     installable only in regions it has sourced
 */
public record InstallConsent(String consentVersion, boolean ageAttested, String region) {}
