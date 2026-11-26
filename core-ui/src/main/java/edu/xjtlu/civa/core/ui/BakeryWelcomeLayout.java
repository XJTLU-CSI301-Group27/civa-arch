package edu.xjtlu.civa.core.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.ImageView;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

/** Shared two-tone scene. Feature XML supplies the form and the greeting copy. */
public final class BakeryWelcomeLayout extends FrameLayout {
    private FrameLayout sheet;
    private View form;
    private ImageView cat;
    private FrameLayout catWindow;
    private TextView greeting;
    private ValueAnimator entrance;
    private float progress;
    private int boundary;
    private boolean played;

    public BakeryWelcomeLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBackgroundColor(ContextCompat.getColor(context, R.color.civa_welcome_brown));
        setClipChildren(false); setClipToPadding(false);
    }
    @Override protected void onFinishInflate() {
        super.onFinishInflate();
        form = getChildAt(0); removeView(form);
        sheet = new RoundedSheet(getContext(), dp(32));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(ContextCompat.getColor(getContext(), R.color.civa_welcome_paper));
        shape.setCornerRadii(new float[]{dp(32),dp(32),dp(32),dp(32),0,0,0,0}); sheet.setBackground(shape);
        // Clip the paper and form explicitly: old Android cannot outline-clip asymmetric corners.
        addView(sheet); sheet.addView(new PaperBackdropView(getContext(), null), new FrameLayout.LayoutParams(-1,-1)); sheet.addView(form);
        catWindow = new FrameLayout(getContext()); catWindow.setClipChildren(true); addView(catWindow);
        cat = new ImageView(getContext()); cat.setImageResource(R.drawable.civa_cat_long_hat);
        cat.setScaleType(ImageView.ScaleType.FIT_CENTER); cat.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        cat.setId(R.id.civa_welcome_cat); catWindow.addView(cat);
        greeting = new TextView(getContext()); greeting.setId(R.id.civa_welcome_greeting);
        greeting.setTextColor(ContextCompat.getColor(getContext(), R.color.civa_primary_dark));
        greeting.setTextSize(19); greeting.setGravity(Gravity.CENTER); greeting.setIncludeFontPadding(false);
        greeting.setTypeface(ResourcesCompat.getFont(getContext(), R.font.civa_kuaile));
        greeting.setPadding(dp(14),dp(10),dp(14),dp(10));
        GradientDrawable bubble = new GradientDrawable(); bubble.setColor(ContextCompat.getColor(getContext(), R.color.civa_surface));
        bubble.setCornerRadius(dp(22)); greeting.setBackground(bubble);
        addView(greeting); setProgress(0);
    }
    public void setGreeting(CharSequence text) { greeting.setText(text); }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(() -> {
            View content = getRootView().findViewById(android.R.id.content);
            if (isAttachedToWindow() && (content == null || !Boolean.TRUE.equals(content.getTag(R.id.civa_intro_blocked)))) playEntrance();
        });
    }
    public void playEntrance() {
        if (played || !isAttachedToWindow()) return;
        played = true;
        entrance = ValueAnimator.ofFloat(0, 1); entrance.setDuration(1100);
        entrance.setInterpolator(new DecelerateInterpolator(1.2f));
        entrance.addUpdateListener(a -> setProgress((Float)a.getAnimatedValue())); entrance.start();
    }
    private void setProgress(float value) {
        progress = value;
        if (sheet == null) return;
        sheet.setTranslationY((getHeight() - boundary) * (1 - Math.min(1, progress / .72f)));
        float catProgress = Math.max(0, Math.min(1, (progress - .12f) / .72f));
        cat.setAlpha(catProgress); cat.setTranslationY(dp(100) * (1-catProgress));
        greeting.setAlpha(catProgress); greeting.setTranslationY(dp(16) * (1-catProgress));
        float formProgress = Math.max(0, Math.min(1, (progress - .4f) / .6f));
        form.setAlpha(formProgress); form.setTranslationY(dp(18) * (1-formProgress));
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width,height);
        boundary = Math.round(height * .26f);
        sheet.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(height-boundary,MeasureSpec.EXACTLY));
        int formWidth = Math.min(width, dp(476));
        form.measure(MeasureSpec.makeMeasureSpec(formWidth,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(height-boundary,MeasureSpec.EXACTLY));
        catWindow.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(boundary+dp(8),MeasureSpec.EXACTLY));
        int catHeight = Math.min(dp(218), Math.max(dp(82),boundary-dp(6)));
        cat.measure(MeasureSpec.makeMeasureSpec(Math.round(catHeight*(2f/3f)),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(catHeight,MeasureSpec.EXACTLY));
        greeting.measure(MeasureSpec.makeMeasureSpec(Math.min(dp(205),Math.round(width*.52f)),MeasureSpec.AT_MOST),MeasureSpec.makeMeasureSpec(dp(80),MeasureSpec.AT_MOST));
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b) {
        if (sheet == null) return;
        int width = r-l, height = b-t, fw = form.getMeasuredWidth();
        sheet.layout(0,boundary,width,height); form.layout((width-fw)/2,0,(width+fw)/2,height-boundary);
        int cw=cat.getMeasuredWidth(),ch=cat.getMeasuredHeight();
        catWindow.layout(0,0,width,boundary+dp(8));
        int cx=Math.round(width*.73f-cw*.5f), bottom=boundary+dp(8);
        cat.layout(cx,bottom-ch,cx+cw,bottom);
        int gw=greeting.getMeasuredWidth(),gh=greeting.getMeasuredHeight();
        int gx=Math.max(dp(20),Math.round(cx+cw*.12f-gw-dp(10)));
        int gy=Math.max(dp(16),boundary-Math.round(ch*.48f));
        greeting.layout(gx,gy,gx+gw,gy+gh); setProgress(progress);
    }
    @Override protected void onDetachedFromWindow() {
        if (entrance != null) { entrance.cancel(); entrance = null; }
        super.onDetachedFromWindow();
    }
    /** Keeps top-only rounded corners visible even when a full-size paper child paints its own background. */
    private static final class RoundedSheet extends FrameLayout {
        private final Path clip = new Path();
        private final float radius;
        RoundedSheet(Context context, float radius) { super(context); this.radius = radius; }
        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            clip.reset();
            float r = Math.min(radius, Math.min(width / 2f, height / 2f));
            if (r > 0) clip.addRoundRect(new RectF(0, 0, width, height),
                    new float[]{r, r, r, r, 0, 0, 0, 0}, Path.Direction.CW);
        }
        @Override protected void dispatchDraw(Canvas canvas) {
            int saved = canvas.save();
            if (!clip.isEmpty()) canvas.clipPath(clip);
            super.dispatchDraw(canvas);
            canvas.restoreToCount(saved);
        }
    }

    private int dp(float n) { return Math.round(n*getResources().getDisplayMetrics().density); }
}
