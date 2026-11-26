package edu.xjtlu.civa.core.ui;

import androidx.annotation.Nullable;
import com.google.android.material.button.MaterialButton;

/** Loading + duplicate-tap guard for network submit buttons. */
public final class SubmitButton {
    private SubmitButton() { }

    public static void start(MaterialButton button) {
        if (button == null || Boolean.TRUE.equals(button.getTag(R.id.civa_submit_busy))) return;
        button.setTag(R.id.civa_submit_label, button.getText());
        button.setTag(R.id.civa_submit_busy, Boolean.TRUE);
        button.setEnabled(false);
        button.setAlpha(0.7f);
        button.setText(R.string.civa_ui_working);
    }

    public static void stop(@Nullable MaterialButton button) {
        if (button == null) return;
        Object label = button.getTag(R.id.civa_submit_label);
        if (label instanceof CharSequence text) button.setText(text);
        button.setTag(R.id.civa_submit_busy, null);
        button.setTag(R.id.civa_submit_label, null);
        button.setEnabled(true);
        button.setAlpha(1f);
    }
}
