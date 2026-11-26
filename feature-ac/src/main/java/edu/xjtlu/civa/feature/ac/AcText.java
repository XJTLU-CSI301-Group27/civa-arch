package edu.xjtlu.civa.feature.ac;

import android.content.res.Resources;
import edu.xjtlu.civa.api.model.Unit;
import java.util.List;
import java.util.Locale;

/** Resource-backed display helpers shared by the AC screens. Canonical values stay unchanged. */
public final class AcText {
  private AcText() {}

  /** The locale of the current (per-app localized) configuration, for date and weekday text. */
  public static Locale locale(Resources resources) {
    return resources.getConfiguration().getLocales().get(0);
  }

  /** Human readable unit label; the canonical {@link Unit} values are never altered. */
  public static String unit(Resources resources, Unit unit, String ingredientName) {
    if (unit == Unit.G) return resources.getString(R.string.ac_l10n_unit_g);
    if (unit == Unit.ML) return resources.getString(R.string.ac_l10n_unit_ml);
    if (unit == Unit.COUNT) {
      String name = ingredientName == null ? "" : ingredientName;
      // i18n: matching keyword - decides bottle versus piece wording from the ingredient name.
      boolean bottle =
          name.contains("奶")
              || name.toLowerCase(Locale.ROOT).matches(".*\\b(milk|yogurt|yoghurt)\\b.*");
      return resources.getString(
          bottle ? R.string.ac_l10n_unit_bottle : R.string.ac_l10n_unit_count);
    }
    return resources.getString(R.string.ac_l10n_unit_unknown);
  }

  /** Joins display parts with the list separator of the current language. */
  public static String join(Resources resources, List<String> parts) {
    return String.join(resources.getString(R.string.ac_l10n_list_separator), parts);
  }
}
