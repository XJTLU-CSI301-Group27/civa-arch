package edu.xjtlu.civa;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import edu.xjtlu.civa.core.ui.MeowSound;

/** Bundled, silent user-supplied CIVA animation; the shared meow is played separately. */
final class AppIntroView extends FrameLayout implements TextureView.SurfaceTextureListener {
    static final long DURATION = 7350;
    private final TextureView video;
    private final TextView bubble;
    private final Runnable done;
    private MediaPlayer player;
    private Surface surface;
    private final android.graphics.Bitmap edgeProbe = android.graphics.Bitmap.createBitmap(16,16,android.graphics.Bitmap.Config.ARGB_8888);
    private int decodedBackground;
    private MeowSound audio;
    private int position;
    private boolean prepared, voiced, finished;
    private final Runnable frame = this::tick;
    private final Runnable preparationTimeout = this::showFallback;
    private final Runnable fallbackDone = this::finish;

    AppIntroView(Context context, long started, boolean alreadyVoiced, Runnable done) {
        super(context);
        this.done = done;
        position = (int)Math.min(7000, Math.max(0, SystemClock.elapsedRealtime() - started));
        voiced = alreadyVoiced;
        setId(R.id.app_intro_scene); setClickable(true); setFocusable(true);
        setBackgroundColor(ContextCompat.getColor(context, edu.xjtlu.civa.core.ui.R.color.civa_welcome_brown));
        setContentDescription(context.getString(R.string.app_intro_description));
        video = new TextureView(context); video.setId(R.id.app_intro_cat);
        video.setOpaque(false); video.setAlpha(0); video.setSurfaceTextureListener(this); addView(video);
        bubble = new TextView(context); bubble.setId(R.id.app_intro_meow);
        bubble.setText(edu.xjtlu.civa.core.ui.R.string.civa_cat_meow);
        bubble.setTextSize(24); bubble.setGravity(Gravity.CENTER);
        bubble.setTypeface(ResourcesCompat.getFont(context, edu.xjtlu.civa.core.ui.R.font.civa_kuaile));
        bubble.setTextColor(ContextCompat.getColor(context, edu.xjtlu.civa.core.ui.R.color.civa_primary_dark));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(ContextCompat.getColor(context, edu.xjtlu.civa.core.ui.R.color.civa_surface));
        shape.setCornerRadius(dp(25)); bubble.setBackground(shape);
        bubble.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        bubble.setVisibility(INVISIBLE); addView(bubble);
    }

    boolean hasVoiced() { return voiced; }
    int playbackMillis() {
        if (player != null && prepared) {
            try { position = player.getCurrentPosition(); } catch (IllegalStateException ignored) { }
        }
        return position;
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow(); audio = new MeowSound(getContext());
        postDelayed(preparationTimeout, 6000);
    }
    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        if (finished || player != null) return;
        surface = new Surface(texture);
        MediaPlayer created = new MediaPlayer(); player = created;
        try (AssetFileDescriptor asset = getResources().openRawResourceFd(R.raw.app_cat_intro)) {
            created.setSurface(surface); created.setVolume(0, 0); created.setLooping(false);
            created.setDataSource(asset.getFileDescriptor(), asset.getStartOffset(), asset.getLength());
            created.setOnInfoListener((source, what, extra) -> {
                if (source == player && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    matchVideoBackground(); video.setAlpha(1); android.util.Log.i("CivaIntro", "video_started");
                }
                return false;
            });
            created.setOnPreparedListener(source -> {
                if (source != player || !isAttachedToWindow() || finished) return;
                prepared = true; removeCallbacks(preparationTimeout);
                if (position > 0) source.seekTo(position, MediaPlayer.SEEK_CLOSEST);
                if (getWindowVisibility() == VISIBLE) { source.start(); postOnAnimation(frame); }
            });
            created.setOnCompletionListener(source -> { if (source == player) { android.util.Log.i("CivaIntro", "video_completed"); finish(); } });
            created.setOnErrorListener((source, what, extra) -> { if (source == player) showFallback(); return true; });
            created.prepareAsync();
        } catch (Exception unavailable) { showFallback(); }
    }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        releasePlayer(); return true;
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) { }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { matchVideoBackground(); }
    private void matchVideoBackground() {
        if (!isAttachedToWindow() || !video.isAvailable() || edgeProbe.isRecycled() || finished) return;
        try {
            // The source video reserves its top-left corner for plain background.
            // Sampling a reusable thumbnail handles small device-specific YUV/RGB differences.
            android.graphics.Bitmap sample = video.getBitmap(edgeProbe);
            if (sample == null) return;
            int color = sample.getPixel(0,0);
            if (android.graphics.Color.alpha(color) < 250 || color == decodedBackground) return;
            decodedBackground = color;
            setBackgroundColor(color);
            View content = getRootView().findViewById(android.R.id.content);
            if (content != null) content.setBackgroundColor(color);
        } catch (IllegalStateException disappearingSurface) { /* Surface can leave during app backgrounding. */ }
    }

    private void tick() {
        if (finished || !prepared || player == null || !isAttachedToWindow() || getWindowVisibility() != VISIBLE) return;
        int now = playbackMillis();
        if (now >= 3000 && !voiced) {
            voiced = true;
            audio.play(() -> {
                if (!isAttachedToWindow() || finished || getWindowVisibility() != VISIBLE) return;
                bubble.setVisibility(VISIBLE); bubble.setScaleX(.6f); bubble.setScaleY(.6f); bubble.setAlpha(0);
                bubble.animate().alpha(1).scaleX(1).scaleY(1).setDuration(220)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f)).start();
            });
        }
        if (now > 3900) bubble.setAlpha(Math.max(0, 1 - (now - 3900) / 350f));
        postDelayed(frame, 32);
    }
    private void finish() {
        if (finished || !isAttachedToWindow()) return;
        finished = true; removeCallbacks(frame); removeCallbacks(preparationTimeout);
        releasePlayer();
        animate().alpha(0).setDuration(220).withEndAction(done).start();
    }
    private void showFallback() {
        if (finished || !isAttachedToWindow()) return;
        android.util.Log.w("CivaIntro", "video_fallback");
        removeCallbacks(preparationTimeout); removeCallbacks(frame); releasePlayer(); video.setVisibility(INVISIBLE);
        ImageView poster = new ImageView(getContext());
        poster.setImageResource(edu.xjtlu.civa.core.ui.R.drawable.civa_cat_long_hat);
        poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
        addView(poster, 0, new LayoutParams(dp(160), dp(230), Gravity.CENTER));
        removeCallbacks(fallbackDone); postDelayed(fallbackDone, 900);
    }
    private void releasePlayer() {
        playbackMillis(); prepared = false;
        if (player != null) {
            player.setOnPreparedListener(null); player.setOnCompletionListener(null); player.setOnErrorListener(null); player.setOnInfoListener(null);
            player.release(); player = null;
        }
        if (surface != null) { surface.release(); surface = null; }
    }
    @Override protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs); setMeasuredDimension(w, h);
        int width = Math.min(dp(312), Math.round(w * .84f));
        int height = Math.round(width * 780f / 720f);
        if (height > h * .65f) { height = Math.round(h * .65f); width = Math.round(height * 720f / 780f); }
        video.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        bubble.measure(MeasureSpec.makeMeasureSpec(dp(68), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(52), MeasureSpec.EXACTLY));
        for (int i=0;i<getChildCount();i++) if(getChildAt(i)!=video&&getChildAt(i)!=bubble) measureChild(getChildAt(i),ws,hs);
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int x=(getWidth()-video.getMeasuredWidth())/2, y=(getHeight()-video.getMeasuredHeight())/2;
        video.layout(x,y,x+video.getMeasuredWidth(),y+video.getMeasuredHeight());
        int bx=Math.min(getWidth()-dp(72),x+Math.round(video.getMeasuredWidth()*.78f));
        bubble.layout(bx,y,bx+dp(68),y+dp(52));
        for(int i=0;i<getChildCount();i++) {
            View child=getChildAt(i); if(child==video||child==bubble)continue;
            int left=(getWidth()-child.getMeasuredWidth())/2, top=(getHeight()-child.getMeasuredHeight())/2;
            child.layout(left,top,left+child.getMeasuredWidth(),top+child.getMeasuredHeight());
        }
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (player != null && prepared) {
            if (visibility == VISIBLE && !finished) { player.start(); removeCallbacks(frame); postOnAnimation(frame); }
            else { player.pause(); removeCallbacks(frame); if (audio != null) audio.stop(); }
        }
    }
    @Override protected void onDetachedFromWindow() {
        removeCallbacks(frame); removeCallbacks(preparationTimeout); removeCallbacks(fallbackDone);
        animate().cancel(); bubble.animate().cancel(); releasePlayer();
        if (audio != null) { audio.close(); audio=null; }
        edgeProbe.recycle(); super.onDetachedFromWindow();
    }
    private int dp(float n) { return Math.round(n*getResources().getDisplayMetrics().density); }
}
