package edu.xjtlu.civa.core.ui;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.os.Bundle;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import com.google.android.material.navigation.NavigationBarView;

/** Shared native press feedback. Touch/key events still reach the original controls. */
public final class PressBounce {
    private static final float PRESSED_SCALE = .94f;
    private static final double STIFFNESS = 260.0, DAMPING = 12.0; // mass = 1, damping coefficient (not ratio)
    private PressBounce() { }

    public static void bind(View view) { bind(view, null); }

    public static void bind(View view, @Nullable View shadow) {
        if (view.getTag(R.id.civa_press_bounce_binding) instanceof Binding) return;
        Binding binding = new Binding(view, shadow);
        view.setTag(R.id.civa_press_bounce_binding, binding);
        view.setOnTouchListener(binding);
        view.setOnKeyListener(binding);
        view.addOnAttachStateChangeListener(binding);
    }

    public static void bindButtons(View root) {
        if (root instanceof ElasticChoiceView) return; // This radio group owns its thumb/drag spring feedback.
        if (root instanceof Button || root instanceof ImageButton) bind(root);
        if (root instanceof NavigationBarView) {
            NavigationBarView navigation = (NavigationBarView) root;
            for (int i = 0; i < navigation.getMenu().size(); i++) {
                View item = navigation.findViewById(navigation.getMenu().getItem(i).getItemId());
                if (item != null) bind(item);
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) bindButtons(group.getChildAt(i));
        }
    }

    public static void install(AppCompatActivity activity) {
        View decor = activity.getWindow().getDecorView();
        if (Boolean.TRUE.equals(decor.getTag(R.id.civa_press_bounce_installed))) return;
        decor.setTag(R.id.civa_press_bounce_installed, Boolean.TRUE);
        bindButtons(decor); decor.post(() -> bindButtons(decor));
        activity.getSupportFragmentManager().registerFragmentLifecycleCallbacks(new FragmentManager.FragmentLifecycleCallbacks() {
            @Override public void onFragmentViewCreated(@NonNull FragmentManager manager, @NonNull Fragment fragment,
                                                        @NonNull View view, @Nullable Bundle state) { bindButtons(view); }
            @Override public void onFragmentStarted(@NonNull FragmentManager manager, @NonNull Fragment fragment) {
                if (fragment.getView() != null) bindButtons(fragment.getView());
                if (fragment instanceof DialogFragment) {
                    Dialog dialog = ((DialogFragment) fragment).getDialog();
                    if (dialog != null && dialog.getWindow() != null) bindButtons(dialog.getWindow().getDecorView());
                }
            }
        }, true);
    }

    private static final class Binding implements View.OnTouchListener, View.OnKeyListener,
            View.OnAttachStateChangeListener, Choreographer.FrameCallback {
        private final View view, shadow;
        private final float elevation, translationZ, shadowAlpha;
        private boolean pressed, running;
        private long releasedAt;
        private float startScale;

        Binding(View view, @Nullable View shadow) {
            this.view = view; this.shadow = shadow;
            if (view.getStateListAnimator() != null) {
                view.getStateListAnimator().jumpToCurrentState();
                view.setStateListAnimator(null); // Material's default pressed lift would conflict with the requested softer shadow.
            }
            // Preserve flat controls: adding elevation to calendar text/arrows casts rectangular shadows.
            // Existing raised controls still soften their own shadow during a press.
            elevation = view.getElevation(); translationZ = view.getTranslationZ();
            shadowAlpha = shadow == null ? 0 : shadow.getAlpha();
        }

        private void down() {
            if (!view.isEnabled()) return;
            stop(); pressed = true;
            if (Motion.enabled()) apply(PRESSED_SCALE);
        }

        private void up() {
            if (!pressed) return;
            pressed = false;
            if (!Motion.enabled()) { stop(); apply(1); return; }
            startScale = view.getScaleX(); releasedAt = 0;
            running = true; Choreographer.getInstance().postFrameCallback(this);
        }

        // We return false: View.onTouchEvent owns performClick, including accessibility activation.
        @SuppressLint("ClickableViewAccessibility")
        @Override public boolean onTouch(View target, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: down(); break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: up(); break;
                case MotionEvent.ACTION_MOVE:
                    if (event.getX() < 0 || event.getY() < 0 || event.getX() > view.getWidth() || event.getY() > view.getHeight()) up();
                    break;
                default: break;
            }
            return false; // Never consume click, long-click, drag or horizontal scrolling.
        }

        @Override public boolean onKey(View target, int code, KeyEvent event) {
            if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_SPACE) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) down();
                else if (event.getAction() == KeyEvent.ACTION_UP) up();
            }
            return false;
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running || !view.isAttachedToWindow()) { reset(); return; }
            if (releasedAt == 0) releasedAt = frameTimeNanos;
            double t = (frameTimeNanos - releasedAt) / 1_000_000_000.0;
            double decay = DAMPING / 2, omega = Math.sqrt(STIFFNESS - decay * decay);
            double displacement = startScale - 1;
            double envelope = Math.exp(-decay * t);
            double position = displacement * envelope * (Math.cos(omega * t) + decay / omega * Math.sin(omega * t));
            double velocity = -displacement * envelope * STIFFNESS / omega * Math.sin(omega * t);
            apply((float) (1 + position));
            if (t > 1.4 || (t > .15 && Math.abs(position) < .0003 && Math.abs(velocity) < .003)) reset();
            else Choreographer.getInstance().postFrameCallback(this);
        }

        private void apply(float scale) {
            view.setScaleX(scale); view.setScaleY(scale);
            float pressure = Math.max(0, Math.min(1, (1 - scale) / (1 - PRESSED_SCALE)));
            float strength = 1 - .6f * pressure;
            view.setElevation(elevation * strength); view.setTranslationZ(translationZ * strength);
            if (shadow != null) shadow.setAlpha(shadowAlpha * strength);
        }

        private void stop() { running = false; Choreographer.getInstance().removeFrameCallback(this); }
        private void reset() { stop(); pressed = false; apply(1); }
        @Override public void onViewAttachedToWindow(View target) { }
        @Override public void onViewDetachedFromWindow(View target) { reset(); }
    }
}
