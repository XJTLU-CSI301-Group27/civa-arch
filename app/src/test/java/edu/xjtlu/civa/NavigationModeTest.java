package edu.xjtlu.civa;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class NavigationModeTest {
    @Test public void narrowWindowsKeepTheBottomBar() {
        assertEquals(NavigationMode.Kind.BOTTOM, NavigationMode.forWidth(360));
        assertEquals(NavigationMode.Kind.BOTTOM, NavigationMode.forWidth(411));
        assertEquals(NavigationMode.Kind.BOTTOM, NavigationMode.forWidth(599));
    }

    @Test public void wideWindowsUseTheLeftRail() {
        assertEquals(NavigationMode.Kind.RAIL, NavigationMode.forWidth(600));
        assertEquals(NavigationMode.Kind.RAIL, NavigationMode.forWidth(800));
        assertEquals(NavigationMode.Kind.RAIL, NavigationMode.forWidth(1280));
    }
}
