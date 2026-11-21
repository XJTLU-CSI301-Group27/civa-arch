package edu.xjtlu.civa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

/**
 * Resource ownership gate: a feature module owns its prefix, references shared {@code civa_*}
 * tokens, and never reaches into the application module or redefines the shared theme.
 */
public class ResourceOwnershipTest {
    private static final Pattern REFERENCE = Pattern.compile(
            "@\\+?(?:drawable|string|id|style|color|font|layout|dimen|bool|integer|menu|xml|array|plurals)/([A-Za-z0-9_.]+)");
    private static final Pattern SHARED_DEFINITION = Pattern.compile("<(?:color|string|style|dimen|bool|integer|item)\\s+name=\"(?:civa_|Civa)");
    private static final String[] FEATURES = {"feature-ac", "feature-bd", "feature-ef"};

    @Test public void featureModulesDoNotReferenceApplicationResources() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String module : FEATURES) {
            for (Path file : resources(module)) {
                String text = read(file);
                Matcher matcher = REFERENCE.matcher(text);
                while (matcher.find()) {
                    if (matcher.group(1).startsWith("app_")) {
                        offenders.add(module + ": " + file.getFileName() + " -> " + matcher.group(1));
                    }
                }
            }
        }
        assertTrue("Feature modules must not reference app_ resources: " + offenders, offenders.isEmpty());
    }

    @Test public void featureModulesDoNotRedefineSharedTokens() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String module : FEATURES) {
            for (Path file : resources(module)) {
                if (SHARED_DEFINITION.matcher(read(file)).find()) {
                    offenders.add(module + ": " + file.getFileName());
                }
            }
        }
        assertTrue("Shared civa_*/Civa* tokens belong to core-ui only: " + offenders, offenders.isEmpty());
    }

    @Test public void applicationModuleDoesNotDefineFeatureResources() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : resources("app")) {
            Matcher matcher = REFERENCE.matcher(read(file));
            while (matcher.find()) {
                String name = matcher.group(1);
                if (name.startsWith("bd_") || name.startsWith("ef_") || name.startsWith("ac_")) {
                    offenders.add("app: " + file.getFileName() + " -> " + name);
                }
            }
        }
        assertTrue("The app module must not consume feature-owned resources directly: " + offenders, offenders.isEmpty());
    }

    private static List<Path> resources(String module) throws IOException {
        Path res = repoRoot().resolve(module).resolve("src/main/res");
        if (!Files.isDirectory(res)) return List.of();
        try (Stream<Path> files = Files.walk(res)) {
            List<Path> result = new ArrayList<>();
            files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".xml"))
                    .forEach(result::add);
            return result;
        }
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static Path repoRoot() {
        String root = System.getProperty("civa.repoRoot");
        if (root == null || root.isEmpty()) throw new IllegalStateException("civa.repoRoot is not configured");
        return Path.of(root);
    }
}
