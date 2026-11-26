package edu.xjtlu.civa.feature.ac.catalog;

import android.content.Context;
import android.content.res.Resources;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import edu.xjtlu.civa.api.model.Ingredient;
import edu.xjtlu.civa.api.model.RecipeIngredient;
import edu.xjtlu.civa.api.model.Unit;
import edu.xjtlu.civa.feature.ac.AcText;
import edu.xjtlu.civa.feature.ac.R;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Small import verification sheet: A/B rows are prefilled, C rows must be corrected, and nothing is
 * created until every row has an ingredient, a positive quantity and a confirmed yield/duration.
 */
final class ImportReviewDialog {
    interface Listener {
        void onConfirm(List<RecipeIngredient> ingredients, String yield, int duration);
    }

    private ImportReviewDialog() { }

    static void show(Context context, List<IngredientImportParser.Line> lines,
                     List<Ingredient> dictionary, String initialYield, Integer initialDuration,
                     Listener listener) {
        if (context == null || lines == null || lines.isEmpty()) return;
        State state = new State(context, lines, dictionary, initialYield, initialDuration, listener);
        state.show();
    }

    private static final class State {
        final Context context;
        final List<Row> rows = new ArrayList<>();
        final List<Ingredient> dictionary;
        final EditText yield;
        final EditText duration;
        final TextView error;
        final Listener listener;
        AlertDialog dialog;
        boolean confirmed;

        State(Context context, List<IngredientImportParser.Line> lines, List<Ingredient> dictionary,
              String initialYield, Integer initialDuration, Listener listener) {
            this.context = context;
            this.dictionary = dictionary == null ? List.of() : dictionary;
            this.listener = listener;
            this.yield = field(context, R.string.ac_import_yield, initialYield == null ? "" : initialYield, false);
            this.duration = field(context, R.string.ac_import_duration,
                    initialDuration == null ? "" : String.valueOf(initialDuration), true);
            this.error = text(context, "", 13, 0xFFB00020);
            this.error.setVisibility(View.GONE);
            for (IngredientImportParser.Line line : lines) rows.add(new Row(context, line, this.dictionary));
        }

        void show() {
            LinearLayout content = column(context);
            content.setPadding(dp(20), dp(4), dp(20), dp(4));
            content.addView(text(context, context.getString(R.string.ac_import_hint), 13,
                    android.graphics.Color.GRAY));

            LinearLayout numbers = row(context);
            numbers.setPadding(0, dp(10), 0, dp(4));
            numbers.addView(yield, weight());
            numbers.addView(duration, weight());
            content.addView(numbers);

            for (Row row : rows) content.addView(row.root);

            content.addView(error);
            ScrollView scroll = new ScrollView(context);
            scroll.setFillViewport(true);
            scroll.addView(content);

            dialog = new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.ac_import_title)
                    .setView(scroll)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.ac_import_confirm, null)
                    .create();
            dialog.setOnShowListener(ignored ->
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> submit()));
            dialog.show();
        }

        private void submit() {
            if (confirmed) return;
            error.setVisibility(View.GONE);
            BigDecimal yieldValue = IngredientImportParser.number(yield.getText().toString());
            if (yieldValue == null || yieldValue.signum() <= 0) {
                fail();
                return;
            }
            int minutes;
            try {
                minutes = Integer.parseInt(duration.getText().toString().trim());
            } catch (NumberFormatException invalid) {
                fail();
                return;
            }
            if (minutes < 1 || minutes > 1440) {
                fail();
                return;
            }

            List<RecipeIngredient> ingredients = new ArrayList<>();
            Set<UUID> ids = new HashSet<>();
            int invalid = 0;
            Row firstInvalid = null;
            for (Row row : rows) {
                if (row.removed) continue;
                int reason = row.validate(ids);
                if (reason != 0) {
                    row.markInvalid(reason);
                    invalid++;
                    if (firstInvalid == null) firstInvalid = row;
                } else {
                    Ingredient ingredient = row.selected();
                    BigDecimal quantity = IngredientImportParser.number(row.quantity.getText().toString());
                    ingredients.add(new RecipeIngredient().ingredientId(ingredient.getId())
                            .quantity(IngredientImportParser.text(quantity))
                            .unit(ingredient.getUnit()).trackStock(true));
                }
            }
            if (invalid > 0) {
                error.setText(context.getString(R.string.ac_import_invalid_rows, invalid));
                error.setVisibility(View.VISIBLE);
                if (firstInvalid != null) firstInvalid.root.requestFocus();
                return;
            }
            if (ingredients.isEmpty() || dictionary.isEmpty()) {
                error.setText(R.string.ac_import_no_rows);
                error.setVisibility(View.VISIBLE);
                return;
            }
            String confirmedYield = IngredientImportParser.text(yieldValue);
            confirmed = true;
            dialog.dismiss();
            listener.onConfirm(ingredients, confirmedYield, minutes);
        }

        private void fail() {
            error.setText(R.string.ac_import_invalid);
            error.setVisibility(View.VISIBLE);
        }

        private LinearLayout.LayoutParams weight() {
            return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        }
    }

    private static final class Row {
        final LinearLayout root;
        final Spinner spinner;
        final EditText quantity;
        final TextView unit;
        final TextView reason;
        final TextView action;
        final List<Ingredient> dictionary;
        final String initialReason;
        boolean removed;

        Row(Context context, IngredientImportParser.Line line, List<Ingredient> dictionary) {
            this.dictionary = dictionary;
            root = column(context);
            root.setPadding(0, dp(10), 0, dp(6));
            root.addView(text(context, context.getString(R.string.ac_import_source,
                    source(line.source())), 12, android.graphics.Color.GRAY));

            LinearLayout selection = row(context);
            selection.setPadding(0, dp(6), 0, 0);
            spinner = new Spinner(context);
            List<String> choices = new ArrayList<>();
            choices.add(context.getString(R.string.ac_import_choose));
            for (Ingredient ingredient : dictionary) choices.add(ingredient.getName());
            ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                    android.R.layout.simple_spinner_item, choices);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinner.setAdapter(adapter);
            selection.addView(spinner, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));

            quantity = field(context, R.string.ac_import_quantity,
                    line.quantity() == null ? "" : line.quantity(), false);
            selection.addView(quantity, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            unit = text(context, "", 13, 0);
            selection.addView(unit);
            root.addView(selection);

            reason = text(context, reasonText(context, line), 12,
                    line.ready() ? android.graphics.Color.GRAY : 0);
            initialReason = reason.getText().toString();
            root.addView(reason);

            action = text(context, context.getString(R.string.ac_import_remove), 12,
                    android.graphics.Color.GRAY);
            action.setPadding(0, dp(4), 0, 0);
            action.setOnClickListener(ignored -> toggleRemoved());
            root.addView(action);

            int selected = indexOf(dictionary, line.ingredientId());
            spinner.setSelection(selected, false);
            spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    updateUnit(context);
                    updateReason(context);
                }

                @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {
                    unit.setText("—");
                }
            });
            quantity.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }

                @Override public void afterTextChanged(Editable s) {
                    updateReason(context);
                }
            });
            updateUnit(context);
        }

        private void updateReason(Context context) {
            int selected = spinner.getSelectedItemPosition();
            reason.setTextColor(android.graphics.Color.GRAY);
            if (selected <= 0) {
                reason.setText(initialReason);
                return;
            }
            String value = quantity.getText().toString().trim();
            if (value.isEmpty()) {
                reason.setText(R.string.ac_import_reason_missing_quantity);
                return;
            }
            BigDecimal number = IngredientImportParser.number(value);
            reason.setText(number == null || number.signum() <= 0
                    ? R.string.ac_import_reason_invalid_quantity : R.string.ac_import_recognized);
        }

        Ingredient selected() {
            int selected = spinner.getSelectedItemPosition();
            return selected > 0 && selected - 1 < dictionary.size() ? dictionary.get(selected - 1) : null;
        }

        int validate(Set<UUID> ids) {
            Ingredient ingredient = selected();
            if (ingredient == null || ingredient.getUnit() == null) {
                return R.string.ac_import_reason_no_ingredient;
            }
            String value = quantity.getText().toString().trim();
            if (value.isEmpty()) return R.string.ac_import_reason_missing_quantity;
            BigDecimal number = IngredientImportParser.number(value);
            if (number == null || number.signum() <= 0) return R.string.ac_import_reason_invalid_quantity;
            if (!ids.add(ingredient.getId())) return R.string.ac_import_reason_duplicate;
            return 0;
        }

        void markInvalid(int reason) {
            this.reason.setText(reason);
            this.reason.setTextColor(0xFFB00020);
        }

        private void toggleRemoved() {
            removed = !removed;
            root.setAlpha(removed ? 0.45f : 1f);
            spinner.setEnabled(!removed);
            quantity.setEnabled(!removed);
            action.setText(removed ? R.string.ac_import_restore : R.string.ac_import_remove);
        }

        private void updateUnit(Context context) {
            int selected = spinner.getSelectedItemPosition();
            Ingredient ingredient = selected > 0 && selected - 1 < dictionary.size()
                    ? dictionary.get(selected - 1) : null;
            if (ingredient == null || ingredient.getUnit() == null) {
                unit.setText("—");
                return;
            }
            unit.setText(AcText.unit(context.getResources(), ingredient.getUnit(), ingredient.getName()));
        }
    }

    private static int indexOf(List<Ingredient> dictionary, UUID id) {
        if (id == null) return 0;
        for (int i = 0; i < dictionary.size(); i++) {
            if (id.equals(dictionary.get(i).getId())) return i + 1;
        }
        return 0;
    }

    private static String reasonText(Context context, IngredientImportParser.Line line) {
        if (line.ready()) {
            if (line.kind() == IngredientImportParser.Kind.CONVERT) {
                String target = line.unit() == null ? "" : AcText.unit(context.getResources(),
                        line.unit(), line.ingredientName());
                return context.getString(R.string.ac_import_converted,
                        line.quantity() + " " + target);
            }
            return context.getString(R.string.ac_import_recognized);
        }
        return switch (line.reason()) {
            case NO_INGREDIENT -> context.getString(R.string.ac_import_reason_no_ingredient);
            case AMBIGUOUS_INGREDIENT -> context.getString(R.string.ac_import_reason_ambiguous);
            case MISSING_QUANTITY -> context.getString(R.string.ac_import_reason_missing_quantity);
            case INVALID_QUANTITY -> context.getString(R.string.ac_import_reason_invalid_quantity);
            case MISSING_UNIT -> context.getString(R.string.ac_import_reason_missing_unit);
            case UNKNOWN_UNIT -> context.getString(R.string.ac_import_reason_unknown_unit);
            case UNIT_MISMATCH -> context.getString(R.string.ac_import_reason_unit_mismatch);
            case APPROXIMATE -> context.getString(R.string.ac_import_reason_approximate);
            case DUPLICATE -> context.getString(R.string.ac_import_reason_duplicate);
            case OUT_OF_RANGE -> context.getString(R.string.ac_import_reason_out_of_range);
            case CONFLICTING_MEASUREMENT -> context.getString(R.string.ac_import_reason_conflict);
            case OK -> context.getString(R.string.ac_import_recognized);
        };
    }

    private static String source(IngredientImportParser.Input input) {
        if (input == null) return "—";
        Set<String> parts = new LinkedHashSet<>();
        add(parts, input.name());
        add(parts, input.quantityPrefix());
        add(parts, input.quantity());
        add(parts, input.unit());
        if (input.note() != null && !input.note().isBlank()) add(parts, input.note());
        if (input.sourceText() != null && !input.sourceText().isBlank()
                && !input.sourceText().equals(input.name())) add(parts, input.sourceText());
        return parts.isEmpty() ? "—" : String.join(" ", parts);
    }

    private static void add(Set<String> parts, String value) {
        if (value != null && !value.trim().isEmpty()) parts.add(value.trim());
    }

    private static EditText field(Context context, int hint, String value, boolean integer) {
        EditText field = new EditText(context);
        field.setHint(hint);
        field.setText(value);
        field.setTextSize(14);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER
                | (integer ? 0 : InputType.TYPE_NUMBER_FLAG_DECIMAL));
        field.setPadding(0, 0, 0, dp(6));
        return field;
    }

    private static TextView text(Context context, String value, int size, int color) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(size);
        if (color != 0) text.setTextColor(color);
        return text;
    }

    private static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private static int dp(float value) {
        return Math.round(value * Resources.getSystem().getDisplayMetrics().density);
    }
}
