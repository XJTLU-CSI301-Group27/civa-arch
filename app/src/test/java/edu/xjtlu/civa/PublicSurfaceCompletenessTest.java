package edu.xjtlu.civa;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.InputStreamReader;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

/**
 * The other half of PublicSurfaceArchTest: registered symbols must exist (that test), and every
 * public type of the shared UI and feature layers must be either registered or explicitly
 * declared internal here. A public class that nobody registered is an unreviewed contract.
 */
public class PublicSurfaceCompletenessTest {
    @Test public void everyPublicSharedOrFeatureTypeIsRegisteredOrDeclaredInternal() throws Exception {
        JsonObject surface = surface();
        Set<String> registered = types(surface.getAsJsonArray("symbols"));
        Set<String> internal = types(surface.getAsJsonArray("internalSymbols"));

        List<String> unregistered = new ArrayList<>();
        for (JavaClass type : new ClassFileImporter().importPackages("edu.xjtlu.civa.core.ui", "edu.xjtlu.civa.feature.bd")) {
            if (type.getName().contains("$")) continue; // nested values are covered by their outer type
            if (isGenerated(type)) continue;
            if (!type.getModifiers().contains(JavaModifier.PUBLIC)) continue;
            if (!registered.contains(type.getName()) && !internal.contains(type.getName())) {
                unregistered.add(type.getName());
            }
        }
        assertTrue("Public types missing from contracts/public-surface.draft.json (register them or list under internalSymbols): "
                + unregistered, unregistered.isEmpty());
    }

    @Test public void registeredTypesAreUnique() throws Exception {
        JsonObject surface = surface();
        Set<String> seen = new LinkedHashSet<>();
        for (String type : types(surface.getAsJsonArray("symbols"))) {
            assertTrue("Duplicate registered type: " + type, seen.add(type));
        }
    }

/** Generated resource, BuildConfig and view-binding classes are not hand-written contracts. */
    private static boolean isGenerated(JavaClass type) {
        String simple = type.getSimpleName();
        return simple.equals("R") || simple.equals("BuildConfig") || type.getPackageName().contains(".databinding");
    }

    private static JsonObject surface() throws Exception {
        Path path = Path.of(System.getProperty("civa.publicSurface"));
        try (var reader = new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static Set<String> types(JsonArray array) {
        Set<String> result = new LinkedHashSet<>();
        if (array == null) return result;
        for (JsonElement element : array) {
            result.add(element.getAsJsonObject().get("type").getAsString());
        }
        return result;
    }
}
