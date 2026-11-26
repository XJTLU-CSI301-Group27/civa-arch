package edu.xjtlu.civa.feature.ac;

import android.content.res.Resources;

/**
 * An {@link IllegalArgumentException} whose text is a string resource. Pure validation helpers can
 * keep throwing a standard exception type while the screen decides how (and in which language) to
 * show it.
 */
public final class AcValidationException extends IllegalArgumentException {
  private final AcMessage message;

  public AcValidationException(AcMessage message) {
    super(null, null);
    this.message = message;
  }

  public AcValidationException(AcMessage message, Throwable cause) {
    super(null, cause);
    this.message = message;
  }

  public AcMessage message() {
    return message;
  }

  public String format(Resources resources) {
    return message.format(resources);
  }
}
