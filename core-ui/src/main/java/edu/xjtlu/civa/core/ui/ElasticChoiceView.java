package edu.xjtlu.civa.core.ui;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.RadioGroup;
import androidx.appcompat.widget.AppCompatRadioButton;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import java.util.function.IntConsumer;

/** App-private two- or three-way choice. Native radio semantics with a separate elastic thumb. */
@SuppressLint("ViewConstructor") // Constructed with labels and a listener, never inflated from XML.
public final class ElasticChoiceView extends FrameLayout implements Choreographer.FrameCallback {
    private static final double STIFFNESS = 200, DAMPING = 14, STRETCH = 1.35;
    private final RadioGroup group;
    private final AppCompatRadioButton[] options;
    private final View thumb;
    private final GradientDrawable thumbFill;
    private final IntConsumer onSelection;
    private final int primary, dark, surface, touchSlop;
    private final Paint overlayText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path thumbClip = new Path();
    private final RectF thumbBounds = new RectF();
    private ValueAnimator colorAnimation;
    private int selected;
    private float downX, downY, slotWidth;
    private double position, velocity, fromPosition, fromVelocity, target, stretch = 1;
    private long started, recoilStarted;
    private boolean updating, dragging, running, recoiling;

    public ElasticChoiceView(Context context, int[] labels, int[] optionIds, int initial, IntConsumer listener) {
        super(context);
        if (labels.length < 2 || labels.length > 3 || optionIds.length != labels.length) {
            throw new IllegalArgumentException("Two or three matching choices required");
        }
        options = new AppCompatRadioButton[labels.length];
        onSelection = listener;
        selected = Math.max(0, Math.min(options.length - 1, initial)); position = selected; target = selected;
        primary = color(R.color.civa_primary);
        dark = color(R.color.civa_primary_dark);
        surface = color(R.color.civa_surface);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setPadding(dp(4), dp(4), dp(4), dp(4)); setMinimumHeight(dp(56));
        setSaveFromParentEnabled(false); // The ViewModel owns selection across restored/recreated dialogs.
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        GradientDrawable track = new GradientDrawable(); track.setColor(surface);
        track.setCornerRadius(dp(28)); track.setStroke(dp(1), color(R.color.civa_outline));
        setBackground(track);
        thumbFill = new GradientDrawable(); thumbFill.setColor(primary); thumbFill.setCornerRadius(dp(24));
        thumb = new View(context); thumb.setBackground(thumbFill);
        thumb.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(thumb, new LayoutParams(0, LayoutParams.MATCH_PARENT));
        group = new RadioGroup(context); group.setOrientation(RadioGroup.HORIZONTAL);
        addView(group, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        for (int i = 0; i < options.length; i++) {
            AppCompatRadioButton button = new AppCompatRadioButton(context);
            button.setId(optionIds[i]); button.setText(labels[i]); button.setButtonDrawable((android.graphics.drawable.Drawable) null);
            button.setBackgroundColor(Color.TRANSPARENT); button.setGravity(Gravity.CENTER);
            button.setPadding(dp(2), 0, dp(2), 0); button.setMinimumWidth(0); button.setMinWidth(0);
            button.setMinHeight(dp(48)); button.setTextSize(13); button.setSingleLine(true);
            button.setTypeface(ResourcesCompat.getFont(context, R.font.civa_kuaile));
            options[i] = button; group.addView(button, new RadioGroup.LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
        }
        group.check(options[selected].getId()); updateText();
        group.setOnCheckedChangeListener((ignored, id) -> {
            if (!updating) for (int i = 0; i < options.length; i++) if (options[i].getId() == id) commit(i);
        });
    }

    private int color(int id) { return ContextCompat.getColor(getContext(), id); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private boolean rtl() { return getLayoutDirection() == LAYOUT_DIRECTION_RTL; }

    private void commit(int value) {
        boolean changed = value != selected;
        selected = value; updating = true; group.check(options[value].getId()); updating = false;
        updateText();
        if (isLaidOut() && isAttachedToWindow()) {
            moveTo(value); tintToTheme();
        } else { stopMotion(); position = value; stretch = 1; placeThumb(); }
        if (changed) onSelection.accept(value);
    }

    private void updateText() {
        // Radio semantics update immediately; visual text color follows the moving capsule.
        for (AppCompatRadioButton option : options) option.setTextColor(dark);
        invalidate();
    }

    private void tintToTheme() {
        if (colorAnimation != null) colorAnimation.cancel();
        colorAnimation = ValueAnimator.ofArgb(dark, primary);
        colorAnimation.setDuration(280);
        colorAnimation.addUpdateListener(animation -> thumbFill.setColor((Integer) animation.getAnimatedValue()));
        colorAnimation.start();
    }

    private void moveTo(double value) {
        stopMotion(); fromPosition = position; fromVelocity = velocity; target = value;
        // Hold the stretch during travel, then release it into its own spring on arrival.
        stretch = STRETCH; recoiling = Math.abs(position - target) < .025;
        started = 0; recoilStarted = 0; running = true; placeThumb();
        Choreographer.getInstance().postFrameCallback(this);
    }

    private double displacement(double initial, double initialVelocity, double t) {
        double decay = DAMPING / 2, omega = Math.sqrt(STIFFNESS - decay * decay);
        return Math.exp(-decay * t) * (initial * Math.cos(omega * t)
                + (initialVelocity + decay * initial) / omega * Math.sin(omega * t));
    }

    @Override public void doFrame(long frameTimeNanos) {
        if (!running || !isAttachedToWindow()) { snapToSelection(); return; }
        if (started == 0) started = frameTimeNanos;
        double t = (frameTimeNanos - started) / 1_000_000_000.0;
        double initial = fromPosition - target;
        double decay = DAMPING / 2, omega = Math.sqrt(STIFFNESS - decay * decay);
        double b = (fromVelocity + decay * initial) / omega;
        double envelope = Math.exp(-decay * t), sine = Math.sin(omega * t), cosine = Math.cos(omega * t);
        position = target + envelope * (initial * cosine + b * sine);
        velocity = envelope * ((b * omega - decay * initial) * cosine - (initial * omega + decay * b) * sine);
        if (!recoiling && (Math.abs(position - target) < .025 || (position - target) * initial <= 0)) recoiling = true;
        if (recoiling) {
            if (recoilStarted == 0) recoilStarted = frameTimeNanos;
            stretch = 1 + displacement(STRETCH - 1, 0, (frameTimeNanos - recoilStarted) / 1_000_000_000.0);
        }
        placeThumb();
        if (t > 1.6 || (recoiling && Math.abs(position - target) < .001 && Math.abs(velocity) < .01
                && Math.abs(stretch - 1) < .001)) snapToSelection();
        else Choreographer.getInstance().postFrameCallback(this);
    }

    private void placeThumb() {
        if (slotWidth <= 0) return;
        float visual = (float) (rtl() ? options.length - 1 - position : position);
        float half = slotWidth * (float) stretch / 2;
        float center = getPaddingLeft() + (visual + .5f) * slotWidth;
        center = Math.max(getPaddingLeft() + half, Math.min(getWidth() - getPaddingRight() - half, center));
        thumb.setTranslationX(center - getPaddingLeft() - slotWidth / 2);
        thumb.setScaleX((float) stretch);
        invalidate();
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (slotWidth <= 0) return;
        float scaledWidth = thumb.getWidth() * thumb.getScaleX();
        float left = thumb.getX() + (thumb.getWidth() - scaledWidth) / 2;
        thumbBounds.set(left, thumb.getTop(), left + scaledWidth, thumb.getBottom());
        thumbClip.rewind();
        thumbClip.addRoundRect(thumbBounds, dp(24) * thumb.getScaleX(), dp(24), Path.Direction.CW);
        int saved = canvas.save(); canvas.clipPath(thumbClip);
        for (AppCompatRadioButton option : options) {
            overlayText.set(option.getPaint()); overlayText.setColor(surface);
            String label = option.getText().toString();
            float x = group.getLeft() + option.getLeft() + (option.getWidth() - overlayText.measureText(label)) / 2;
            canvas.drawText(label, x, group.getTop() + option.getTop() + option.getBaseline(), overlayText);
        }
        canvas.restoreToCount(saved);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        slotWidth = (getWidth() - getPaddingLeft() - getPaddingRight()) / (float) options.length;
        thumb.layout(getPaddingLeft(), getPaddingTop(), getPaddingLeft() + Math.round(slotWidth), getHeight() - getPaddingBottom());
        placeThumb();
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX(); downY = event.getY(); dragging = false; break;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(event.getX() - downX) > touchSlop && Math.abs(event.getX() - downX) > Math.abs(event.getY() - downY)) {
                    stopMotion(); velocity = 0; dragging = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    dragTo(event.getX()); return true;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                if (dragging) moveTo(selected);
                dragging = false; break;
            default: break;
        }
        return false;
    }

    // Actual taps stay on the native RadioButtons. This handles only intercepted drags/gaps.
    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: downX = event.getX(); downY = event.getY(); return true;
            case MotionEvent.ACTION_MOVE: dragging = true; dragTo(event.getX()); return true;
            case MotionEvent.ACTION_UP:
                int value = Math.max(0, Math.min(options.length - 1, Math.round((float) position)));
                if (!dragging) value = indexAt(event.getX());
                dragging = false; getParent().requestDisallowInterceptTouchEvent(false);
                performClick(); commit(value); return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false; getParent().requestDisallowInterceptTouchEvent(false); moveTo(selected); return true;
            default: return super.onTouchEvent(event);
        }
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    private int indexAt(float x) {
        int physical = slotWidth <= 0 ? 0 : Math.max(0, Math.min(options.length - 1, (int) ((x - getPaddingLeft()) / slotWidth)));
        return rtl() ? options.length - 1 - physical : physical;
    }

    private void dragTo(float x) {
        float physical = slotWidth <= 0 ? 0 : (x - getPaddingLeft()) / slotWidth - .5f;
        position = Math.max(0, Math.min(options.length - 1, rtl() ? options.length - 1 - physical : physical));
        stretch = STRETCH; placeThumb();
    }

    private void stopMotion() { running = false; Choreographer.getInstance().removeFrameCallback(this); }
    private void snapToSelection() { stopMotion(); position = selected; velocity = 0; stretch = 1; placeThumb(); }
    @Override protected void onDetachedFromWindow() {
        snapToSelection(); dragging = false;
        if (colorAnimation != null) colorAnimation.cancel();
        thumbFill.setColor(primary); super.onDetachedFromWindow();
    }
}
