package edu.xjtlu.civa.core.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.util.function.Supplier;

/** Places the existing menu beside the selected plate; decorations live only in this dialog. */
@SuppressLint("ViewConstructor") // Built programmatically around the existing dialog content.
public final class DishActionOverlay extends FrameLayout {
    private final View menu;
    private final ImageView cat;
    private final Supplier<Rect> sourceAnchor;
    private final Supplier<View> sourceRoot;
    private final Rect lastSource = new Rect(), placementAnchor = new Rect();
    private final int[] screenOrigin = new int[2];
    private final OvershootInterpolator catSpring = new OvershootInterpolator(1.1f);
    private boolean anchored, hasSource;
    private View observed;
    private final ViewTreeObserver.OnPreDrawListener sourceDraw = this::onSourceDraw;
    private boolean onSourceDraw() {
        if (anchored) {
            Rect next = sourceAnchor.get();
            if (next == null ? hasSource : !hasSource || !lastSource.equals(next)) {
                hasSource = next != null;
                if (next != null) lastSource.set(next);
                requestLayout();
            }
        }
        return true;
    }

    public DishActionOverlay(Context context, View content, Supplier<Rect> anchor, Supplier<View> source) {
        super(context); menu = content; sourceAnchor = anchor; sourceRoot = source;
        setId(R.id.civa_dish_action_overlay); menu.setId(R.id.civa_dish_action_menu);
        addView(menu, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        cat = new ImageView(context); cat.setId(R.id.civa_dish_peek_cat);
        cat.setImageResource(R.drawable.civa_cat_peeking); cat.setScaleType(ImageView.ScaleType.FIT_XY);
        cat.setImageAlpha(255);
        cat.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); cat.setVisibility(GONE);
        cat.setElevation(dp(2)); cat.setRotation(DishPopoverPlacement.CAT_ROTATION);
        addView(cat, new LayoutParams(dp(32), dp(38)));
        setClipChildren(false); setClipToPadding(false);
    }

    public void setAnchored(boolean value) {
        setClickable(value);
        if (anchored == value) return;
        anchored = value; hasSource = false;
        setClickable(value); menu.setClickable(value); menu.setElevation(value ? dp(2) : 0);
        menu.setBackgroundResource(value ? R.drawable.civa_dish_popover_background : R.drawable.civa_dish_dialog_background);
        cat.animate().cancel();
        // Include the sticker in measurement before its first visible layout.
        cat.setVisibility(value ? INVISIBLE : GONE);
        bindSource(); requestLayout();
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private Rect localAnchor() {
        Rect bounds = sourceAnchor.get();
        if (bounds == null) return null;
        getLocationOnScreen(screenOrigin); bounds.offset(-screenOrigin[0], -screenOrigin[1]);
        return bounds;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        if (!anchored) { super.onMeasure(widthSpec, heightSpec); return; }
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        Rect anchor = localAnchor();
        Rect occupied = anchor == null ? null : occupiedAnchor(anchor, width, height);
        int menuWidth = occupied == null ? Math.min(dp(228), width - dp(24))
                : DishPopoverPlacement.menuWidth(width, occupied.left, occupied.right, dp(228), dp(214), dp(12), dp(8));
        menu.measure(MeasureSpec.makeMeasureSpec(Math.max(1, menuWidth), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(1, height - dp(24)), MeasureSpec.AT_MOST));
        cat.measure(MeasureSpec.makeMeasureSpec(dp(32), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(38), MeasureSpec.EXACTLY));
        setMeasuredDimension(width, height);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (!anchored) { super.onLayout(changed, left, top, right, bottom); return; }
        bindSource();
        int width = getWidth(), height = getHeight(), w = menu.getMeasuredWidth(), h = menu.getMeasuredHeight();
        Rect anchor = localAnchor();
        if (anchor == null) {
            menu.layout((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2);
            cat.animate().cancel(); cat.setVisibility(INVISIBLE); return;
        }
        Rect occupied = occupiedAnchor(anchor, width, height);
        DishPopoverPlacement.Position at = DishPopoverPlacement.place(width, height, occupied.left, occupied.top,
                occupied.right, occupied.bottom, w, h, dp(12), dp(8));
        menu.layout(at.x, at.y, at.x + w, at.y + h);
        DishPopoverPlacement.CatPose pose = catPose(anchor, width, height);
        cat.layout(pose.x, pose.y, pose.x + cat.getMeasuredWidth(), pose.y + cat.getMeasuredHeight());
        if (cat.getVisibility() != VISIBLE) {
            cat.setVisibility(VISIBLE); cat.setAlpha(0f); cat.setScaleX(.8f); cat.setScaleY(.8f); cat.setTranslationY(dp(5));
            // Float alpha is intentional: ImageView.setAlpha(int) changes image opacity on a 0..255 scale.
            cat.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0).setDuration(180)
                    .setInterpolator(catSpring).start();
        }
    }

    private DishPopoverPlacement.CatPose catPose(Rect plate, int width, int height) {
        return DishPopoverPlacement.catPose(width, height, plate.left, plate.top, plate.right, plate.bottom,
                dp(32), dp(38), dp(3), dp(8));
    }

    private Rect occupiedAnchor(Rect plate, int width, int height) {
        DishPopoverPlacement.CatPose pose = catPose(plate, width, height);
        placementAnchor.set(plate); placementAnchor.union(pose.left, pose.top, pose.right, pose.bottom);
        return placementAnchor;
    }

    private void bindSource() {
        View next = anchored && isAttachedToWindow() ? sourceRoot.get() : null;
        if (observed == next) return;
        if (observed != null && observed.getViewTreeObserver().isAlive()) observed.getViewTreeObserver().removeOnPreDrawListener(sourceDraw);
        observed = next;
        if (observed != null && observed.getViewTreeObserver().isAlive()) observed.getViewTreeObserver().addOnPreDrawListener(sourceDraw);
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); bindSource(); }
    @Override protected void onDetachedFromWindow() {
        if (observed != null && observed.getViewTreeObserver().isAlive()) observed.getViewTreeObserver().removeOnPreDrawListener(sourceDraw);
        observed = null; cat.animate().cancel(); cat.setVisibility(GONE); super.onDetachedFromWindow();
    }
}
