package edu.xjtlu.civa.core.ui;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

/** App-level language choice; applied before any activity and persisted across restarts. */
public final class LocalePrefs {
    public static final String CHINESE = "zh-CN";
    public static final String ENGLISH = "en";
    private static final String PREFS = "civa_locale";
    private static final String KEY = "language";

    private LocalePrefs() { }

    public static void apply(Context context) {
        String tag = stored(context);
        if (tag != null) AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag));
    }

    public static void set(Context context, String tag) {
        prefs(context).edit().putString(KEY, tag).apply();
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag));
    }

    public static boolean isEnglish(Context context) {
        return ENGLISH.equals(stored(context));
    }

    private static String stored(Context context) {
        return prefs(context).getString(KEY, null);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
