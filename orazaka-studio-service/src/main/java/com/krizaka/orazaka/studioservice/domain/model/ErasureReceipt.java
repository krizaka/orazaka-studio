package com.krizaka.orazaka.studioservice.domain.model;

/**
 * What an erasure removed - counts only, never content.
 *
 * @param packKey the pack whose data was erased
 * @param entries runs removed (inputs and outputs)
 * @param installations installations removed, with the consent they recorded
 * @param jobs jobs removed from the job plane (rows and files)
 */
public record ErasureReceipt(String packKey, int entries, int installations, int jobs) {}
