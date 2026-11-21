package edu.xjtlu.civa.core.ui;

import android.content.Context;
import java.io.IOException;
import java.io.InputStream;

/**
 * Image reference resolution for {@link DishPlateView}. core-ui does not know where a
 * feature stores photos, so the owning module installs a loader once at startup.
 * Until then a reference resolves to nothing and the plate falls back to its drawn
 * kitten illustration, which keeps the widget usable and offline.
 */
public final class ImageSources {
    /** Opens the bytes behind a feature-specific image reference. Must not run on the main thread. */
    public interface Loader {
        InputStream open(Context context, String reference) throws IOException;
    }

    private static volatile Loader loader;

    private ImageSources() { }

    /** Installs the process-wide loader. Passing null restores the drawn fallback. */
    public static void setLoader(Loader value) { loader = value; }

    public static InputStream open(Context context, String reference) throws IOException {
        Loader current = loader;
        if (current == null) throw new IOException("No image source installed");
        return current.open(context, reference);
    }
}
