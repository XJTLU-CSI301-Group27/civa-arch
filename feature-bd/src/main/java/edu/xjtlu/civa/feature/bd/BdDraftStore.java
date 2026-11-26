package edu.xjtlu.civa.feature.bd;

import android.content.Context;
import android.util.AtomicFile;
import com.google.gson.Gson;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Personal account drafts, independent of household membership; old sources are retained during migration. */
final class BdDraftStore {
    private final AtomicFile file;
    private final Context context;
    private final String scope;

    BdDraftStore(Context context, String scope) throws IOException {
        this.scope = scope;
        this.context = context.getApplicationContext();
        File directory = new File(context.getFilesDir(), "home-plan-drafts");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create draft storage");
        file = new AtomicFile(new File(directory, scope + ".json"));
    }

    BdDraft load() throws IOException {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile().getPath() + ".bak").exists()) return BdDraft.empty(scope);
        byte[] contents;
        try (FileInputStream input = file.openRead()) { contents = read(input); }
        return BdDraft.decode(new String(contents, StandardCharsets.UTF_8), scope);
    }

    BdDraft loadMigrating(java.util.List<String> ownedLegacyScopes) throws IOException {
        BdDraft current = load();
        for (String legacyScope : new java.util.LinkedHashSet<>(ownedLegacyScopes)) {
            if (!legacyScope.matches("[0-9a-f]{64}") || legacyScope.equals(scope) || current.migratedScopes.contains(legacyScope)) continue;
            BdDraftStore legacy = new BdDraftStore(context, legacyScope);
            if (!legacy.file.getBaseFile().exists() && !new File(legacy.file.getBaseFile().getPath() + ".bak").exists()) continue;
            try {
                current = current.importLegacy(legacy.load(), reference -> {
                    try { return copyLegacyPhoto(reference, legacyScope); }
                    catch (IOException error) { throw new java.io.UncheckedIOException(error); }
                });
                save(current);
            } catch (java.io.UncheckedIOException error) { throw error.getCause(); }
        }
        return current;
    }

    private String copyLegacyPhoto(String reference, String legacyScope) throws IOException {
        String prefix = "local:home-dish-photos/" + legacyScope + "/";
        if (!reference.startsWith(prefix)) throw new IOException("Photo owner mismatch");
        String name = reference.substring(prefix.length());
        if (!name.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9]+")) throw new IOException("Invalid photo filename");
        File source = new File(context.getFilesDir(), reference.substring(6));
        if (source.length() > 20 * 1024 * 1024) throw new IOException("Photo too large");
        File directory = new File(context.getFilesDir(), "home-dish-photos/" + scope);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create photo storage");
        String targetName = legacyScope + "-" + name;
        AtomicFile target = new AtomicFile(new File(directory, targetName));
        FileOutputStream output = target.startWrite();
        try (FileInputStream input = new FileInputStream(source)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync(); target.finishWrite(output);
        } catch (IOException | RuntimeException error) { target.failWrite(output); throw error; }
        return "local:home-dish-photos/" + scope + "/" + targetName;
    }

    void save(BdDraft data) throws IOException {
        byte[] bytes = new Gson().toJson(data).getBytes(StandardCharsets.UTF_8);
        FileOutputStream output = file.startWrite();
        try {
            output.write(bytes);
            output.getFD().sync();
            file.finishWrite(output);
        } catch (IOException | RuntimeException error) {
            file.failWrite(output);
            throw error;
        }
        try (FileInputStream input = file.openRead()) {
            if (!Arrays.equals(bytes, read(input))) throw new IOException("Draft write was not confirmed");
        }
    }

    private static byte[] read(FileInputStream input) throws IOException {
        if (input.getChannel().size() > 16 * 1024 * 1024) throw new IOException("Draft too large");
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }
}
