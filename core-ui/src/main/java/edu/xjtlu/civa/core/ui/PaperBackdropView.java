package edu.xjtlu.civa.core.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import java.util.Random;

/** A subtle, repeatable paper grain drawn locally, with no external image assets. */
public final class PaperBackdropView extends View {
    private final Paint grain = new Paint();
    private final int paper;

    public PaperBackdropView(Context context, @Nullable AttributeSet attributes) {
        super(context, attributes);
        paper = ContextCompat.getColor(context, R.color.civa_background);
        Bitmap tile = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        Random random = new Random(301);
        int[] pixels = new int[192 * 192];
        for (int i = 0; i < pixels.length; i++) {
            boolean light = random.nextBoolean();
            pixels[i] = Color.argb(random.nextInt(14), light ? 255 : 100,
                    light ? 253 : 92, light ? 240 : 70);
        }
        tile.setPixels(pixels, 0, 192, 0, 0, 192, 192);
        BitmapShader shader = new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        Matrix scale = new Matrix();
        scale.setScale(getResources().getDisplayMetrics().density, getResources().getDisplayMetrics().density);
        shader.setLocalMatrix(scale);
        grain.setShader(shader);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(paper);
        canvas.drawRect(0, 0, getWidth(), getHeight(), grain);
    }
}
