package edu.xjtlu.civa;

/** Chooses the shell navigation for the current window width: bottom bar on narrow, rail on wide. */
final class NavigationMode {
    enum Kind { BOTTOM, RAIL }

    /** 600dp is the Material breakpoint between compact and medium/expanded windows. */
    static final int RAIL_MIN_WIDTH_DP = 600;

    private NavigationMode() { }

    static Kind forWidth(int widthDp) {
        return widthDp >= RAIL_MIN_WIDTH_DP ? Kind.RAIL : Kind.BOTTOM;
    }
}
