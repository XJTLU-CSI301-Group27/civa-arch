package edu.xjtlu.civa.core.ui;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

/** Small bundled sound: no network, permissions, speech service or forced volume changes. */
public final class MeowSound implements AutoCloseable {
    private final SoundPool pool;
    private final int sample;
    private boolean ready, closed, failed;
    private Runnable pending;
    private int stream;

    public MeowSound(Context context) {
        pool = new SoundPool.Builder().setMaxStreams(1).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        pool.setOnLoadCompleteListener((ignored, id, status) -> {
            if (closed) return;
            if (status != 0) { failed = true; Runnable waiting = pending; pending = null; if (waiting != null) waiting.run(); return; }
            ready = true;
            Runnable waiting = pending; pending = null;
            if (waiting != null) play(waiting);
        });
        sample = pool.load(context.getApplicationContext(), R.raw.civa_meow, 1);
    }
    public void play(Runnable onStart) {
        if (closed) return;
        if (failed) { onStart.run(); return; }
        if (!ready) { pending = onStart; return; }
        if (stream != 0) pool.stop(stream);
        stream = pool.play(sample, .72f, .72f, 1, 0, 1f);
        onStart.run();
    }
    public void stop() {
        pending = null;
        if (!closed && stream != 0) pool.stop(stream);
        stream = 0;
    }
    @Override public void close() {
        if (closed) return;
        stop(); closed = true; pool.setOnLoadCompleteListener(null); pool.release();
    }
}
