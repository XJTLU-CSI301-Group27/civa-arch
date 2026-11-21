package edu.xjtlu.civa.core.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Original vector-drawn floral plate and rice-kitten fallback, with circular center cropping. */
public final class DishPlateView extends View {
    private static final ExecutorService IMAGES = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path circle = new Path();
    private final Path mark = new Path();
    private final RectF bounds = new RectF();
    private final RectF destination = new RectF();
    private final int ink, paper, clay;
    private Bitmap image;
    private String reference;
    private int request;

    public DishPlateView(Context context) { this(context, null); }
    public DishPlateView(Context context, @Nullable AttributeSet attributes) {
        super(context, attributes);
        ink = ContextCompat.getColor(context, R.color.civa_primary_dark);
        paper = ContextCompat.getColor(context, R.color.civa_surface);
        clay = ContextCompat.getColor(context, R.color.civa_secondary);
    }

    public void setImageReference(@Nullable String value) {
        if (java.util.Objects.equals(reference, value) && (image != null || value == null)) return;
        reference = value;
        image = value == null ? null : CACHE.get(value);
        request++;
        invalidate();
        if (value != null && image == null) load(value, request);
    }

    private void load(String value, int generation) {
        Context context = getContext().getApplicationContext();
        IMAGES.execute(() -> {
            Bitmap loaded = null;
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                try (InputStream input = ImageSources.open(context, value)) { BitmapFactory.decodeStream(input, null, bounds); }
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 1;
                while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 768) options.inSampleSize *= 2;
                try (InputStream input = ImageSources.open(context, value)) { loaded = BitmapFactory.decodeStream(input, null, options); }
                if (loaded != null && Math.max(loaded.getWidth(), loaded.getHeight()) > 384) {
                    float scale = 384f / Math.max(loaded.getWidth(), loaded.getHeight());
                    Bitmap small = Bitmap.createScaledBitmap(loaded, Math.max(1, Math.round(loaded.getWidth() * scale)), Math.max(1, Math.round(loaded.getHeight() * scale)), true);
                    if (small != loaded) loaded.recycle();
                    loaded = small;
                }
                if (loaded != null) CACHE.put(value, loaded);
            } catch (Exception ignored) { /* Keep the honest no-photo illustration if an image is unreadable. */ }
            Bitmap result = loaded;
            MAIN.post(() -> {
                if (generation == request && value.equals(reference) && isAttachedToWindow()) { image = result; invalidate(); }
            });
        });
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (reference != null && image == null) { image = CACHE.get(reference); if (image == null) load(reference, ++request); }
    }

    @Override protected void onDetachedFromWindow() { request++; super.onDetachedFromWindow(); }
    @Override public CharSequence getAccessibilityClassName() { return ImageView.class.getName(); }
    @Override public boolean performClick() { super.performClick(); return true; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) / 2f - dp(3);
        if (r <= 0) return;
        paint.setStyle(Paint.Style.FILL); paint.setColor(paper);
        canvas.drawCircle(cx, cy, r, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Math.max(dp(0.7f), r * .009f)); paint.setColor(clay);
        canvas.drawCircle(cx, cy, r * .985f, paint);
        canvas.drawCircle(cx, cy, r * .805f, paint);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 24; i++) {
            double angle = i * Math.PI / 12;
            float x = cx + (float) Math.cos(angle) * r * .902f;
            float y = cy + (float) Math.sin(angle) * r * .902f;
            canvas.save(); canvas.rotate((float) Math.toDegrees(angle) + 10, x, y);
            paint.setColor(i % 3 == 0 ? ink : clay); paint.setAlpha(i % 3 == 0 ? 140 : 185);
            bounds.set(x - r * .025f, y - r * .048f, x + r * .025f, y + r * .048f);
            canvas.drawOval(bounds, paint); canvas.restore();
        }
        paint.setAlpha(255);
        if (image != null && !image.isRecycled()) {
            float inner = r * .785f;
            circle.reset(); circle.addCircle(cx, cy, inner, Path.Direction.CW);
            canvas.save(); canvas.clipPath(circle);
            float scale = Math.max(2 * inner / image.getWidth(), 2 * inner / image.getHeight());
            float width = image.getWidth() * scale, height = image.getHeight() * scale;
            destination.set(cx - width / 2, cy - height / 2, cx + width / 2, cy + height / 2);
            canvas.drawBitmap(image, null, destination, paint); canvas.restore();
        } else drawKitten(canvas, cx, cy, r);
    }

    private void drawKitten(Canvas canvas, float x, float y, float r) {
        paint.setColor(ink); paint.setStrokeWidth(Math.max(dp(1.4f), r * .022f));
        paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND); paint.setStyle(Paint.Style.STROKE);
        mark.reset();
        mark.moveTo(x - r * .5f, y + r * .15f);
        mark.cubicTo(x - r * .65f, y - r * .25f, x - r * .48f, y - r * .6f, x - r * .26f, y - r * .35f);
        mark.quadTo(x, y - r * .48f, x + r * .25f, y - r * .35f);
        mark.cubicTo(x + r * .48f, y - r * .61f, x + r * .64f, y - r * .18f, x + r * .5f, y + r * .15f);
        mark.quadTo(x + r * .45f, y + r * .56f, x, y + r * .56f);
        mark.quadTo(x - r * .45f, y + r * .56f, x - r * .5f, y + r * .15f);
        canvas.drawPath(mark, paint);
        canvas.drawLine(x - r * .12f, y - r * .32f, x - r * .18f, y - r * .18f, paint);
        canvas.drawLine(x + r * .04f, y - r * .34f, x + r * .13f, y - r * .19f, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(x - r * .2f, y + r * .03f, r * .026f, paint);
        canvas.drawCircle(x + r * .2f, y + r * .03f, r * .026f, paint);
        paint.setColor(Color.rgb(209, 158, 143)); paint.setAlpha(105);
        bounds.set(x - r * .4f, y + r * .13f, x - r * .2f, y + r * .22f); canvas.drawOval(bounds, paint);
        bounds.offset(r * .6f, 0); canvas.drawOval(bounds, paint);
        paint.setAlpha(255); paint.setColor(ink); paint.setStyle(Paint.Style.STROKE);
        mark.reset(); mark.moveTo(x - r * .06f, y + r * .18f); mark.lineTo(x, y + r * .25f); mark.lineTo(x + r * .06f, y + r * .18f);
        canvas.drawPath(mark, paint);
        for (int i = 0; i < 2; i++) {
            float yy = y + r * (.1f + i * .12f);
            canvas.drawLine(x - r * .49f, yy, x - r * .34f, yy + r * .03f, paint);
            canvas.drawLine(x + r * .34f, yy + r * .03f, x + r * .49f, yy, paint);
        }
        paint.setStyle(Paint.Style.FILL); paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(r * .112f);
        canvas.drawText(getResources().getString(R.string.civa_dish_no_photo), x, y - r * .61f, paint);
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}
