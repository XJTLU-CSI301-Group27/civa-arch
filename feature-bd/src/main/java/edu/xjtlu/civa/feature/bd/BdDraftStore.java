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

/** Private, atomic local drafts; the server remains the authority for shared household plans. */
final class BdDraftStore {
    private final AtomicFile file;
    private final String scope;

    BdDraftStore(Context context, String scope) throws IOException {
        this.scope = scope;
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
