package edu.xjtlu.civa.feature.ac;

import android.content.res.Resources;
import androidx.annotation.StringRes;

/**
 * A string resource with arguments that is resolved later, once the (already localized)
 * {@link Resources} of a screen is available. Nested messages are supported so composed text,
 * such as "error. retry" or "prefix: value", can be localized as one unit without baking
 * language-specific text into non-Android classes.
 */
public final class AcMessage {
  private final int resource;
  private final Object[] args;

  private AcMessage(int resource, Object[] args) {
    this.resource = resource;
    this.args = args;
  }

  public static AcMessage of(@StringRes int resource, Object... args) {
    return new AcMessage(resource, args == null ? new Object[0] : args);
  }

  public int resource() {
    return resource;
  }

  public String format(Resources resources) {
    if (args.length == 0) return resources.getString(resource);
    Object[] resolved = new Object[args.length];
    for (int i = 0; i < args.length; i++) {
      Object value = args[i];
      resolved[i] = value instanceof AcMessage nested ? nested.format(resources) : value;
    }
    return resources.getString(resource, resolved);
  }
}
