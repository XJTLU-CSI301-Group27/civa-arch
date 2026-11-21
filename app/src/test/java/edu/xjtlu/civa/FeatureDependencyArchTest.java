package edu.xjtlu.civa;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.Test;
import static org.junit.Assert.assertTrue;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Bytecode gate from PROJECT_DOC.md 14.6: features must not reach OkHttp, direct
 * URL connections or sockets. They submit calls through ApiCalls and may only
 * reference the generated Retrofit Call type, not call enqueue/execute.
 */
public class FeatureDependencyArchTest {
    private static final JavaClasses FEATURES = new ClassFileImporter()
            .importPackages("edu.xjtlu.civa.feature");

    @Test public void featuresMustNotDependOnNetworkTransportDirectly() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("edu.xjtlu.civa.feature..")
                .should().dependOnClassesThat().resideInAnyPackage("okhttp3..", "java.net..");
        rule.check(FEATURES);
    }

    /** A rule that scans an empty classpath passes silently; fail instead of reporting green. */
    @Test public void featureClassesAreActuallyOnTheTestClasspath() {
        assertTrue("No classes under edu.xjtlu.civa.feature were imported; the rule would pass vacuously.",
                FEATURES.size() > 0);
    }
}
