package edu.xjtlu.civa.core.ui;

/** Pure window-local geometry shared by the popover and its placement regression tests. */
public final class DishPopoverPlacement {
    public enum Side { RIGHT, LEFT, ABOVE, BELOW, FALLBACK }
    public static final class Position {
        public final int x, y;
        public final Side side;
        Position(int x, int y, Side side) { this.x = x; this.y = y; this.side = side; }
    }
    public static final float CAT_ROTATION = -35f;
    static final class CatPose {
        final int x, y, left, top, right, bottom;
        CatPose(int x, int y, int w, int h, double halfW, double halfH) {
            this.x = x; this.y = y;
            double cx = x + w / 2.0, cy = y + h / 2.0;
            left = (int) Math.floor(cx - halfW); top = (int) Math.floor(cy - halfH);
            right = (int) Math.ceil(cx + halfW); bottom = (int) Math.ceil(cy + halfH);
        }
    }
    private DishPopoverPlacement() { }

    public static CatPose catPose(int width, int height, int left, int top, int right, int bottom,
                           int catW, int catH, int platePadding, int margin) {
        double angle = Math.toRadians(-CAT_ROTATION), sine = Math.sin(angle), cosine = Math.cos(angle);
        double radius = Math.max(0, Math.min(right - left, bottom - top) / 2.0 - platePadding);
        // The two paws at 90% of sticker height rest on the plate's upper-left circular rim.
        double rimX = (left + right) / 2.0 - radius / Math.sqrt(2);
        double rimY = (top + bottom) / 2.0 - radius / Math.sqrt(2);
        double halfW = cosine * catW / 2 + sine * catH / 2;
        double halfH = sine * catW / 2 + cosine * catH / 2;
        double cx = rimX - sine * catH * .4, cy = rimY - cosine * catH * .4;
        cx = Math.max(margin + halfW, Math.min(width - margin - halfW, cx));
        cy = Math.max(margin + halfH, Math.min(height - margin - halfH, cy));
        return new CatPose((int) Math.round(cx - catW / 2.0), (int) Math.round(cy - catH / 2.0), catW, catH, halfW, halfH);
    }

    public static int menuWidth(int screenWidth, int anchorLeft, int anchorRight, int maximum, int minimumSide, int margin, int gap) {
        int full = Math.min(maximum, Math.max(1, screenWidth - 2 * margin));
        int side = Math.max(anchorLeft - gap - margin, screenWidth - margin - anchorRight - gap);
        return side >= minimumSide ? Math.min(full, side) : full;
    }

    public static Position place(int width, int height, int left, int top, int right, int bottom,
                          int menuWidth, int menuHeight, int margin, int gap) {
        int spaceRight = width - margin - right - gap, spaceLeft = left - margin - gap;
        int x, y; Side side;
        if (spaceRight >= menuWidth && spaceRight >= spaceLeft) {
            side = Side.RIGHT; x = right + gap; y = (top + bottom - menuHeight) / 2;
        } else if (spaceLeft >= menuWidth) {
            side = Side.LEFT; x = left - gap - menuWidth; y = (top + bottom - menuHeight) / 2;
        } else if (spaceRight >= menuWidth) {
            side = Side.RIGHT; x = right + gap; y = (top + bottom - menuHeight) / 2;
        } else if (top - gap - margin >= menuHeight) {
            side = Side.ABOVE; x = (left + right - menuWidth) / 2; y = top - gap - menuHeight;
        } else if (height - margin - bottom - gap >= menuHeight) {
            side = Side.BELOW; x = (left + right - menuWidth) / 2; y = bottom + gap;
        } else {
            side = Side.FALLBACK; x = (left + right - menuWidth) / 2;
            y = top >= height - bottom ? top - gap - menuHeight : bottom + gap;
        }
        return new Position(clamp(x, margin, width - margin - menuWidth),
                clamp(y, margin, height - margin - menuHeight), side);
    }

    static int clamp(int value, int minimum, int maximum) { return Math.max(minimum, Math.min(Math.max(minimum, maximum), value)); }
}
