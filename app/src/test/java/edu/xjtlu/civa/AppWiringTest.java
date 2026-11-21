package edu.xjtlu.civa;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

/**
 * Wiring gate: the application shell must actually route the main destination to the feature
 * that implements it. A placeholder here compiles cleanly but ships an "unimplemented"
 * screen, which is exactly the failure this test exists to prevent.
 */
public class AppWiringTest {
    @Test public void todayRouteIsWiredToTheBdHomeFragment() {
        JavaClasses classes = new ClassFileImporter().importPackages("edu.xjtlu.civa");
        JavaClass navigator = classes.stream()
                .filter(type -> type.getName().equals("edu.xjtlu.civa.AppNavigator"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("AppNavigator is missing from the classpath"));
        boolean wired = false;
        for (Dependency dependency : navigator.getDirectDependenciesFromSelf()) {
            if (dependency.getTargetClass().getName().equals("edu.xjtlu.civa.feature.bd.BdHomeFragment")) {
                wired = true;
                break;
            }
        }
        assertTrue("AppNavigator does not reference BdHomeFragment; Route.TODAY would fall back to a placeholder",
                wired);
    }
}
