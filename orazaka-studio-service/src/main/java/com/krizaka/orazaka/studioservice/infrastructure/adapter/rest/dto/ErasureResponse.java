package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

import com.krizaka.orazaka.studioservice.domain.model.ErasureReceipt;

/**
 * What an erasure removed - counts, never content.
 *
 * @param packKey the pack
 * @param entries entries removed, with their inputs and outputs
 * @param installations installations removed, with the consent they recorded
 * @param jobs jobs removed from the job plane, rows and files
 * @param conversations conversations whose memory (voice and listening turns) was removed
 */
public record ErasureResponse(
    String packKey, int entries, int installations, int jobs, int conversations) {

  /**
   * @param receipt the receipt
   * @return its wire form
   */
  public static ErasureResponse from(ErasureReceipt receipt) {
    return new ErasureResponse(
        receipt.packKey(),
        receipt.entries(),
        receipt.installations(),
        receipt.jobs(),
        receipt.conversations());
  }
}
