package edu.xjtlu.civa.core.ui;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import com.google.android.material.snackbar.Snackbar;
import java.lang.ref.WeakReference;

/** One transient feedback channel for the whole app: bakery-themed snackbars. */
public final class Feedback {
    private static WeakReference<Snackbar> current = new WeakReference<>(null);

    private Feedback() { }

    public static void show(Activity activity, int text, boolean error) {
        show(activity, activity.getString(text), error);
    }

    public static void show(Activity activity, CharSequence message, boolean error) {
        if (activity == null || activity.isFinishing() || message == null) return;
        View root = activity.findViewById(android.R.id.content);
        if (root == null) return;
        Snackbar previous = current.get();
        if (previous != null && previous.isShown()) previous.dismiss();
        Snackbar bar = Snackbar.make(root, message, error ? Snackbar.LENGTH_LONG : Snackbar.LENGTH_SHORT);
        bar.setBackgroundTint(ContextCompat.getColor(activity,
                error ? R.color.civa_primary_dark : R.color.civa_text));
        bar.setTextColor(ContextCompat.getColor(activity, R.color.civa_surface));
        bar.setActionTextColor(ContextCompat.getColor(activity, R.color.civa_surface));
        current = new WeakReference<>(bar);
        bar.show();
    }

    public static void dismiss() {
        Snackbar previous = current.get();
        if (previous != null && previous.isShown()) previous.dismiss();
    }
}
