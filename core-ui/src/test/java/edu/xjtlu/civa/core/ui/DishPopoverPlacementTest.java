package edu.xjtlu.civa.core.ui;

import static org.junit.Assert.*;
import org.junit.Test;

public class DishPopoverPlacementTest {
    private void inWindow(DishPopoverPlacement.Position p, int width, int height, int w, int h) {
        assertTrue(p.x >= 14); assertTrue(p.y >= 14);
        assertTrue(p.x + w <= width - 14); assertTrue(p.y + h <= height - 14);
    }
    @Test public void phoneUsesFullWidthAboveBreakfastWithoutCoveringPlate() {
        int w = DishPopoverPlacement.menuWidth(411, 34, 102, 400, 300, 14, 8);
        assertEquals(383, w);
        var p = DishPopoverPlacement.place(411, 866, 34, 373, 102, 441, w, 346, 14, 8);
        assertEquals(DishPopoverPlacement.Side.ABOVE, p.side);
        assertTrue(p.y + 346 + 8 <= 373); inWindow(p, 411, 866, w, 346);
    }
    @Test public void wideWindowPlacesMenuRightOfLeftmostPlate() {
        int w = DishPopoverPlacement.menuWidth(914, 34, 102, 400, 300, 14, 8);
        assertEquals(400, w);
        var p = DishPopoverPlacement.place(914, 560, 34, 370, 102, 438, w, 346, 14, 8);
        assertEquals(DishPopoverPlacement.Side.RIGHT, p.side);
        assertTrue(p.x >= 110); inWindow(p, 914, 560, w, 346);
    }
    @Test public void rightEdgeFlipsToLeftAndStaysInside() {
        var p = DishPopoverPlacement.place(914, 560, 800, 370, 868, 438, 400, 346, 14, 8);
        assertEquals(DishPopoverPlacement.Side.LEFT, p.side);
        assertTrue(p.x + 400 + 8 <= 800); inWindow(p, 914, 560, 400, 346);
    }
    @Test public void topPlateUsesBelowWhenNeitherSideFits() {
        var p = DishPopoverPlacement.place(411, 866, 180, 60, 248, 128, 383, 346, 14, 8);
        assertEquals(DishPopoverPlacement.Side.BELOW, p.side);
        assertTrue(p.y >= 136); inWindow(p, 411, 866, 383, 346);
    }
    @Test public void dinnerPlateUsesAboveAndDoesNotFallOffBottom() {
        var p = DishPopoverPlacement.place(411, 866, 180, 745, 248, 813, 383, 346, 14, 8);
        assertEquals(DishPopoverPlacement.Side.ABOVE, p.side);
        assertTrue(p.y + 346 + 8 <= 745); inWindow(p, 411, 866, 383, 346);
    }
    @Test public void mediumWindowShrinksOnlyWhenUsableSideWidthRemains() {
        int w = DishPopoverPlacement.menuWidth(600, 180, 248, 400, 300, 14, 8);
        assertEquals(330, w);
        var p = DishPopoverPlacement.place(600, 700, 180, 300, 248, 368, w, 346, 14, 8);
        assertEquals(DishPopoverPlacement.Side.RIGHT, p.side); inWindow(p, 600, 700, w, 346);
    }
    @Test public void constrainedWindowClampsFallbackAndMargins() {
        var p = DishPopoverPlacement.place(320, 400, 140, 150, 208, 218, 292, 346, 14, 8);
        assertEquals(DishPopoverPlacement.Side.FALLBACK, p.side); inWindow(p, 320, 400, 292, 346);
        assertEquals(292, DishPopoverPlacement.menuWidth(320, 34, 102, 400, 300, 14, 8));
    }

    @Test public void compactPhoneMenuFitsBesidePlateInsteadOfFillingScreen() {
        int width = DishPopoverPlacement.menuWidth(411, 14, 102, 228, 214, 12, 8);
        assertEquals(228, width);
        var p = DishPopoverPlacement.place(411, 866, 14, 345, 102, 441, width, 256, 12, 8);
        assertEquals(DishPopoverPlacement.Side.RIGHT, p.side);
        assertTrue(p.x >= 110); assertTrue(p.x + width <= 399);
    }
    @Test public void kittenPawsRestOnUpperLeftPlateRim() {
        var p = DishPopoverPlacement.catPose(411, 866, 34, 373, 102, 445, 32, 38, 3, 8);
        double angle = Math.toRadians(-DishPopoverPlacement.CAT_ROTATION);
        double pawX = p.x + 16 + Math.sin(angle) * 38 * .4;
        double pawY = p.y + 19 + Math.cos(angle) * 38 * .4;
        assertEquals(68 - 31 / Math.sqrt(2), pawX, .75);
        assertEquals(409 - 31 / Math.sqrt(2), pawY, .75);
        assertTrue(p.x < 34); assertTrue(p.y < 373);
    }
    @Test public void rotatedKittenRemainsInsideWindowAtEdges() {
        var p = DishPopoverPlacement.catPose(320, 400, 5, 10, 73, 82, 32, 38, 3, 8);
        assertTrue(p.left >= 7); assertTrue(p.top >= 7);
        assertTrue(p.right <= 313); assertTrue(p.bottom <= 393);
    }
}
