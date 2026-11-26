package edu.xjtlu.civa.core.ui;

import android.animation.ValueAnimator;

/** Shared motion tokens; every custom animation must consult {@link #enabled()}. */
public final class Motion {
    public static final long MICRO = 90L;
    public static final long SHORT = 140L;
    public static final long MEDIUM = 200L;
    public static final long LONG = 300L;

    private Motion() { }

    /** False when the user disabled animations (developer options or accessibility). */
    public static boolean enabled() {
        return ValueAnimator.areAnimatorsEnabled();
    }
}
