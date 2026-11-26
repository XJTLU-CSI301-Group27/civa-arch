package edu.xjtlu.civa.feature.bd;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.text.TextPaint;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.widget.TextViewCompat;

/** Gives empty and populated meals identical bounds; only the hero absorbs spare space. */
public final class BdHomeLayout extends LinearLayout {
    private Boolean compactTitle;
    public BdHomeLayout(Context context, AttributeSet attrs) { super(context, attrs); }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
            float density = getResources().getDisplayMetrics().density;
            float height = (MeasureSpec.getSize(heightSpec) - getPaddingTop() - getPaddingBottom()) / density;
            float font = Math.min(1.6f, getResources().getConfiguration().fontScale);
            int header = Math.round(Math.max(50, 44 * font) * density);
            int label = Math.round(Math.max(34, 33 * font) * density);
            float reserved = header / density + (82 + 20) + label / density * 3;
            int card = Math.round(Math.max(66, Math.min(112, (height - reserved) / 3)) * density);
            float heroHeight = height - header / density - (label + card) / density * 3 - 20;
            boolean compact = heroHeight < 150;
            if (compactTitle == null || compactTitle != compact) {
                TextView title = findViewById(R.id.bd_home_hero_title);
                String text = getResources().getString(R.string.bd_home_hero_title);
                title.setText(compact ? text.replace(String.valueOf((char) 10), "") : text);
                title.setMaxLines(compact ? 1 : 2);
                // wrap_content + framework autosize can measure a previously shrunken height.
                // Fit from the parent's budget instead, so a transient resize cannot latch a tiny size.
                TextViewCompat.setAutoSizeTextTypeWithDefaults(title, TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE);
                compactTitle = compact;
            }
            fitTitle(compact, heroHeight * density, MeasureSpec.getSize(widthSpec));
            size(R.id.bd_home_header, header);
            for (int id : new int[]{R.id.bd_home_breakfast_heading, R.id.bd_home_lunch_heading, R.id.bd_home_dinner_heading}) size(id, label);
            for (int id : new int[]{R.id.bd_home_breakfast_card, R.id.bd_home_lunch_card, R.id.bd_home_dinner_card}) size(id, card);
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    private void fitTitle(boolean compact, float heroHeight, int layoutWidth) {
        TextView title = findViewById(R.id.bd_home_hero_title);
        View container = (View) title.getParent();
        int width = Math.min(container.getLayoutParams().width,
                layoutWidth - getPaddingLeft() - getPaddingRight())
                - container.getPaddingLeft() - container.getPaddingRight()
                - title.getPaddingLeft() - title.getPaddingRight();
        View hero = findViewById(R.id.bd_home_hero);
        float height = heroHeight - hero.getPaddingTop() - hero.getPaddingBottom()
                - title.getPaddingTop() - title.getPaddingBottom();
        String[] lines = title.getText().toString().split("\\n", -1);
        TextPaint paint = new TextPaint(title.getPaint());
        float pixels = 0;
        for (int sp = compact ? 25 : 38; sp >= 16; sp--) {
            pixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                    getResources().getDisplayMetrics());
            paint.setTextSize(pixels);
            float widest = 0;
            for (String line : lines) widest = Math.max(widest, paint.measureText(line));
            float lineHeight = paint.getFontMetrics().descent - paint.getFontMetrics().ascent;
            float textHeight = lineHeight * lines.length + title.getLineSpacingExtra() * (lines.length - 1);
            if (widest <= width && textHeight <= height) break;
        }
        if (Math.abs(title.getTextSize() - pixels) > .01f) title.setTextSize(TypedValue.COMPLEX_UNIT_PX, pixels);
    }

    private void size(int id, int height) {
        View view = findViewById(id);
        // Mutate before the normal measurement pass; do not enqueue another layout from onMeasure.
        view.getLayoutParams().height = height;
    }
}
