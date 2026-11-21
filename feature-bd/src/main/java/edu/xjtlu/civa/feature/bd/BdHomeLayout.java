package edu.xjtlu.civa.feature.bd;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
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
            boolean wide = ((LinearLayout) findViewById(R.id.bd_home_meals)).getOrientation() == HORIZONTAL;
            int header = Math.round(Math.max(wide ? 44 : 50, 44 * font) * density);
            int label = Math.round(Math.max(34, 33 * font) * density);
            float reserved = header / density + (wide ? 64 : 82 + 20) + label / density * (wide ? 1 : 3);
            int card = Math.round(Math.max(66, Math.min(112, (height - reserved) / (wide ? 1 : 3))) * density);
            float heroHeight = height - header / density - (label + card) / density * (wide ? 1 : 3) - (wide ? 0 : 20);
            boolean compact = wide || heroHeight < 150;
            if (compactTitle == null || compactTitle != compact) {
                TextView title = findViewById(R.id.bd_home_hero_title);
                String text = getResources().getString(R.string.bd_home_hero_title);
                title.setText(compact ? text.replace(String.valueOf((char) 10), "") : text);
                title.setMaxLines(compact ? 1 : 2);
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(title, 16, compact ? 24 : 34, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
                findViewById(R.id.bd_home_hero_caption).setVisibility(compact && heroHeight < 92 ? View.GONE : View.VISIBLE);
                compactTitle = compact;
            }
            size(R.id.bd_home_header, header);
            for (int id : new int[]{R.id.bd_home_breakfast_heading, R.id.bd_home_lunch_heading, R.id.bd_home_dinner_heading}) size(id, label);
            for (int id : new int[]{R.id.bd_home_breakfast_card, R.id.bd_home_lunch_card, R.id.bd_home_dinner_card}) size(id, card);
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    private void size(int id, int height) {
        View view = findViewById(id);
        // Mutate before the normal measurement pass; do not enqueue another layout from onMeasure.
        view.getLayoutParams().height = height;
    }
}
