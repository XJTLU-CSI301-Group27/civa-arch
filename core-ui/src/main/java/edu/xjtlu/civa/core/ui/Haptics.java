package edu.xjtlu.civa.core.ui;

import android.view.HapticFeedbackConstants;
import android.view.View;

/** System haptics: respects the user's haptic feedback setting, never bypasses it. */
public final class Haptics {
    private Haptics() { }

    public static void tap(View view) {
        if (view != null) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    public static void confirm(View view) {
        if (view != null) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }
}
