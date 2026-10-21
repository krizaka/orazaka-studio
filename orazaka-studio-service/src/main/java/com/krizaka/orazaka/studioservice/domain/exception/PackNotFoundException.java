package com.krizaka.orazaka.studioservice.domain.exception;

import java.io.Serial;

/** A pack key that names no pack in the catalogue. */
public class PackNotFoundException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final String packKey;

  /**
   * @param packKey the key that resolved to nothing
   */
  public PackNotFoundException(String packKey) {
    super("No such pack: " + packKey);
    this.packKey = packKey;
  }

  /**
   * @return the key that resolved to nothing
   */
  public String packKey() {
    return packKey;
  }
}
