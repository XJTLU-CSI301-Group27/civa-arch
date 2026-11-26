package edu.xjtlu.civa.core.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

/** Leaves the supplied artwork unchanged; adds a separate contact shadow and transient speech bubble. */
public final class BakeryCatStage extends FrameLayout {
    private final View shadow;
    private final TextView bubble;
    private ImageView artwork;
    private MeowSound voice;
    private final Runnable hideBubble = this::fadeBubble;

    public BakeryCatStage(Context context, AttributeSet attributes) {
        super(context, attributes);
        shadow = new ContactShadow(context); shadow.setAlpha(.55f); shadow.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(shadow, new LayoutParams(1, 1));
        bubble = new TextView(context); bubble.setId(R.id.civa_cat_meow_bubble);
        bubble.setText(R.string.civa_cat_meow); bubble.setTextSize(16); bubble.setGravity(Gravity.CENTER);
        bubble.setIncludeFontPadding(false); bubble.setTypeface(ResourcesCompat.getFont(context, R.font.civa_kuaile));
        bubble.setTextColor(ContextCompat.getColor(context, R.color.civa_primary_dark));
        GradientDrawable circle = new GradientDrawable(); circle.setShape(GradientDrawable.OVAL);
        circle.setColor(ContextCompat.getColor(context, R.color.civa_surface));
        circle.setStroke(dp(1), ContextCompat.getColor(context, R.color.civa_outline));
        bubble.setBackground(circle); bubble.setElevation(dp(2)); bubble.setVisibility(INVISIBLE);
        bubble.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        setClipChildren(false); setClipToPadding(false);
    }

    @Override protected void onFinishInflate() {
        super.onFinishInflate();
        artwork = findViewById(R.id.civa_cat_art);
        artwork.setPadding(0, 0, 0, dp(8));
        artwork.setClickable(true); artwork.setFocusable(true); artwork.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        artwork.setContentDescription(getResources().getString(R.string.civa_cat_greeting));
        artwork.setOnClickListener(ignored -> meow());
        PressBounce.bind(artwork, shadow);
        addView(bubble, new LayoutParams(dp(34), dp(34)));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (artwork == null || artwork.getDrawable() == null) return;
        float availableWidth = artwork.getWidth() - artwork.getPaddingLeft() - artwork.getPaddingRight();
        float availableHeight = artwork.getHeight() - artwork.getPaddingTop() - artwork.getPaddingBottom();
        float scale = Math.min(availableWidth / artwork.getDrawable().getIntrinsicWidth(), availableHeight / artwork.getDrawable().getIntrinsicHeight());
        float width = artwork.getDrawable().getIntrinsicWidth() * scale, height = artwork.getDrawable().getIntrinsicHeight() * scale;
        float x = artwork.getLeft() + artwork.getPaddingLeft() + (availableWidth - width) / 2;
        float y = artwork.getTop() + artwork.getPaddingTop() + (availableHeight - height) / 2;
        int shadowWidth = Math.round(width * .65f), shadowHeight = dp(10);
        int shadowX = Math.round(x + width / 2 - shadowWidth / 2f);
        int shadowY = Math.min(getHeight() - shadowHeight, Math.round(y + height - dp(2)));
        shadow.layout(shadowX, shadowY, shadowX + shadowWidth, shadowY + shadowHeight);
        int size = dp(34), bubbleX = Math.round(x + width * .86f - size / 2f);
        int bubbleY = Math.max(0, Math.round(y + height * .08f - size * .8f));
        bubbleX = Math.max(0, Math.min(getWidth() - size, bubbleX));
        bubble.layout(bubbleX, bubbleY, bubbleX + size, bubbleY + size);
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); voice = new MeowSound(getContext()); }
    private void meow() { if (voice != null) voice.play(this::showMeowBubble); }
    private void showMeowBubble() {
        removeCallbacks(hideBubble); bubble.animate().cancel();
        bubble.setVisibility(VISIBLE); bubble.setAlpha(0); bubble.setScaleX(.6f); bubble.setScaleY(.6f); bubble.setTranslationY(dp(3));
        bubble.animate().alpha(1).scaleX(1).scaleY(1).translationY(0).setDuration(130)
                .setInterpolator(new OvershootInterpolator(1.2f)).start();
        postDelayed(hideBubble, 620);
    }

    private void fadeBubble() {
        bubble.animate().alpha(0).scaleX(.8f).scaleY(.8f).translationY(-dp(5)).setDuration(180)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(() -> bubble.setVisibility(INVISIBLE)).start();
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(hideBubble); bubble.animate().cancel(); bubble.setVisibility(INVISIBLE);
        if (voice != null) { voice.close(); voice = null; }
        super.onDetachedFromWindow();
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static final class ContactShadow extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ContactShadow(Context context) {
            super(context);
            paint.setShader(new RadialGradient(0, 0, 1, new int[]{0x55795034, 0x22795034, 0x00795034}, new float[]{0, .5f, 1}, Shader.TileMode.CLAMP));
        }
        @Override protected void onDraw(Canvas canvas) {
            canvas.save(); canvas.translate(getWidth() / 2f, getHeight() / 2f); canvas.scale(getWidth() / 2f, getHeight() / 2f);
            canvas.drawCircle(0, 0, 1, paint); canvas.restore();
        }
    }
}
