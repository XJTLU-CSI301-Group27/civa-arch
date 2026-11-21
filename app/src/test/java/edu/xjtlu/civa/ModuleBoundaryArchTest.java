package edu.xjtlu.civa;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.Assert.assertTrue;

/**
 * Module boundary gate from PROJECT_DOC.md 6.2 and the allowedDependencies table in
 * contracts/public-surface.draft.json: features may only depend on api-client, core-ui and
 * core-data; they never see the application module or each other, and the shared layers do
 * not reach upwards.
 */
public class ModuleBoundaryArchTest {
    private static final JavaClasses CLASSES = new ClassFileImporter().importPackages("edu.xjtlu.civa");

    @Test public void featuresDoNotDependOnTheApplicationModule() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("edu.xjtlu.civa.feature..")
                .should().dependOnClassesThat().resideInAPackage("edu.xjtlu.civa")
                .because("assembly belongs to the app module; features talk to host interfaces they own");
        rule.check(CLASSES);
    }

    @Test public void featureModulesDoNotDependOnEachOther() {
        SlicesRuleDefinition.slices().matching("edu.xjtlu.civa.feature.(*)..")
                .should().notDependOnEachOther()
                .because("each feature is owned by a different group and integrated only through app")
                .check(CLASSES);
    }

    @Test public void coreUiDoesNotDependOnDataOrFeatures() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("edu.xjtlu.civa.core.ui..")
                .should().dependOnClassesThat().resideInAnyPackage("edu.xjtlu.civa.core.data..", "edu.xjtlu.civa.feature..")
                .because("core-ui is the lowest shared layer");
        rule.check(CLASSES);
    }

    @Test public void coreDataDoesNotDependOnFeatures() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("edu.xjtlu.civa.core.data..")
                .should().dependOnClassesThat().resideInAnyPackage("edu.xjtlu.civa.feature..")
                .because("features are consumers of core-data, never providers");
        rule.check(CLASSES);
    }

    /** A slice rule over an empty package set passes silently; fail instead of reporting green. */
    @Test public void featureClasspathIsNotEmpty() {
        long featureClasses = CLASSES.stream()
                .filter(type -> type.getPackageName().startsWith("edu.xjtlu.civa.feature"))
                .count();
        assertTrue("No feature classes imported; the boundary rules would pass vacuously.", featureClasses > 0);
    }
}
