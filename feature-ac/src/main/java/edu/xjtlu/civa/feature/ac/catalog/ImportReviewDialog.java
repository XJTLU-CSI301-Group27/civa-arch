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
import edu.xjtlu.civa.api.model.RecipeOnlyIngredient;
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
 * Import verification sheet. A/B default to TRACKED, C starts undecided, and either type can be
 * chosen explicitly. RECIPE_ONLY keeps the original free text and never fabricates an amount.
 */
final class ImportReviewDialog {
    interface Listener {
        void onConfirm(List<RecipeIngredient> tracked, List<RecipeOnlyIngredient> recipeOnly,
                       String yield, int duration);
    }

    private ImportReviewDialog() { }

    static void show(Context context, List<IngredientImportParser.Line> lines,
                     List<Ingredient> dictionary, String initialYield, Integer initialDuration,
                     Listener listener) {
        if (context == null || lines == null || lines.isEmpty()) return;
        new State(context, lines, dictionary, initialYield, initialDuration, listener).show();
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
                fail(R.string.ac_import_invalid);
                return;
            }
            int minutes;
            try {
                minutes = Integer.parseInt(duration.getText().toString().trim());
            } catch (NumberFormatException invalid) {
                fail(R.string.ac_import_invalid);
                return;
            }
            if (minutes < 1 || minutes > 1440) {
                fail(R.string.ac_import_invalid);
                return;
            }

            List<RecipeIngredient> tracked = new ArrayList<>();
            List<RecipeOnlyIngredient> only = new ArrayList<>();
            Set<UUID> ids = new HashSet<>();
            int invalid = 0;
            Row firstInvalid = null;
            for (Row row : rows) {
                if (row.removed) continue;
                ImportRowState.Error reason = row.state.validate(ids, dictionary);
                if (reason != ImportRowState.Error.NONE) {
                    row.markInvalid(reason);
                    invalid++;
                    if (firstInvalid == null) firstInvalid = row;
                    continue;
                }
                if (row.state.isTracked()) {
                    RecipeIngredient item = row.state.toTracked(dictionary);
                    if (item == null) {
                        row.markInvalid(ImportRowState.Error.INGREDIENT);
                        invalid++;
                        if (firstInvalid == null) firstInvalid = row;
                    } else {
                        tracked.add(item);
                    }
                } else {
                    RecipeOnlyIngredient item = row.state.toRecipeOnly();
                    if (item == null) {
                        row.markInvalid(ImportRowState.Error.NAME);
                        invalid++;
                        if (firstInvalid == null) firstInvalid = row;
                    } else {
                        only.add(item);
                    }
                }
            }
            if (invalid > 0) {
                error.setText(context.getString(R.string.ac_import_invalid_rows, invalid));
                error.setVisibility(View.VISIBLE);
                if (firstInvalid != null) firstInvalid.root.requestFocus();
                return;
            }
            if (tracked.isEmpty() && only.isEmpty()) {
                fail(R.string.ac_import_no_rows);
                return;
            }
            String confirmedYield = IngredientImportParser.text(yieldValue);
            confirmed = true;
            dialog.dismiss();
            listener.onConfirm(tracked, only, confirmedYield, minutes);
        }

        private void fail(int message) {
            error.setText(message);
            error.setVisibility(View.VISIBLE);
        }
    }

    private static final class Row {
        final LinearLayout root;
        final ImportRowState state;
        final List<Ingredient> dictionary;
        final Spinner mode, trackedSpinner;
        final EditText quantity, recipeName, amount, note;
        final TextView unit, reason, action, preview;
        boolean removed;

        Row(Context context, IngredientImportParser.Line line, List<Ingredient> dictionary) {
            this.dictionary = dictionary;
            this.state = new ImportRowState(line);
            root = column(context);
            root.setPadding(0, dp(10), 0, dp(6));
            root.addView(text(context, context.getString(R.string.ac_import_source,
                    source(line.source())), 12, android.graphics.Color.GRAY));

            mode = new Spinner(context);
            List<String> modes = new ArrayList<>();
            modes.add(context.getString(R.string.ac_import_mode_choose));
            modes.add(context.getString(R.string.ac_import_mode_tracked));
            modes.add(context.getString(R.string.ac_import_mode_recipe_only));
            ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(context,
                    android.R.layout.simple_spinner_item, modes);
            modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            mode.setAdapter(modeAdapter);
            mode.setSelection(modeIndex(state.mode()), false);
            mode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    state.mode(modeFrom(position));
                    updateControls(context);
                }
                @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            });
            root.addView(mode, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout tracked = row(context);
            tracked.setPadding(0, dp(4), 0, 0);
            trackedSpinner = new Spinner(context);
            List<String> choices = new ArrayList<>();
            choices.add(context.getString(R.string.ac_import_choose));
            for (Ingredient ingredient : dictionary) choices.add(ingredient.getName());
            ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                    android.R.layout.simple_spinner_item, choices);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            trackedSpinner.setAdapter(adapter);
            trackedSpinner.setSelection(indexOf(dictionary, state.trackedId()), false);
            trackedSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    state.trackedId(position > 0 && position - 1 < dictionary.size()
                            ? dictionary.get(position - 1).getId() : null);
                    updateUnit(context);
                    updateReason(context);
                }
                @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            });
            tracked.addView(trackedSpinner, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
            quantity = field(context, R.string.ac_import_quantity, state.quantity() == null ? "" : state.quantity(), false);
            quantity.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
                @Override public void afterTextChanged(Editable s) {
                    state.quantity(s.toString());
                    updateReason(context);
                }
            });
            tracked.addView(quantity, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            unit = text(context, "", 13, 0);
            tracked.addView(unit);
            root.addView(tracked);

            LinearLayout free = column(context);
            free.setPadding(0, dp(4), 0, 0);
            recipeName = fieldText(context, R.string.ac_import_recipe_only_name, state.recipeName());
            recipeName.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
                @Override public void afterTextChanged(Editable s) {
                    state.recipeName(s.toString());
                    updatePreview(context);
                    updateReason(context);
                }
            });
            free.addView(recipeName);
            amount = fieldText(context, R.string.ac_import_amount, state.amountText());
            amount.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
                @Override public void afterTextChanged(Editable s) {
                    state.amountText(s.toString());
                    updatePreview(context);
                    updateReason(context);
                }
            });
            free.addView(amount);
            note = fieldText(context, R.string.ac_import_note, state.note());
            note.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
                @Override public void afterTextChanged(Editable s) {
                    state.note(s.toString());
                    updatePreview(context);
                    updateReason(context);
                }
            });
            free.addView(note);
            root.addView(free);

            preview = text(context, "", 12, android.graphics.Color.GRAY);
            root.addView(preview);
            reason = text(context, "", 12, android.graphics.Color.GRAY);
            root.addView(reason);
            action = text(context, context.getString(R.string.ac_import_remove), 12, android.graphics.Color.GRAY);
            action.setPadding(0, dp(4), 0, 0);
            action.setOnClickListener(ignored -> toggleRemoved());
            root.addView(action);

            updateControls(context);
        }

        private void updateControls(Context context) {
            boolean tracked = state.isTracked();
            boolean only = state.isRecipeOnly();
            trackedSpinner.setEnabled(tracked);
            quantity.setEnabled(tracked);
            recipeName.setEnabled(only);
            amount.setEnabled(only);
            note.setEnabled(only);
            if (state.hasDecision()) {
                root.setAlpha(1f);
            } else {
                root.setAlpha(0.95f);
            }
            updateUnit(context);
            updatePreview(context);
            updateReason(context);
        }

        private void updatePreview(Context context) {
            if (!state.isRecipeOnly()) {
                preview.setText("");
                return;
            }
            RecipeOnlyIngredient value = state.toRecipeOnly();
            preview.setText(value == null ? "" : context.getString(R.string.ac_import_final_text,
                    value.getName()
                            + (value.getAmountText() == null ? "" : " " + value.getAmountText())
                            + (value.getNote() == null ? "" : " (" + value.getNote() + ")")));
        }

        private void updateReason(Context context) {
            reason.setTextColor(android.graphics.Color.GRAY);
            if (state.mode() == ImportRowState.Mode.NONE) {
                reason.setText(R.string.ac_import_reason_choose);
                return;
            }
            if (state.isTracked()) {
                int position = trackedSpinner.getSelectedItemPosition();
                Ingredient selected = position > 0 && position - 1 < dictionary.size()
                        ? dictionary.get(position - 1) : null;
                String original = state.line().source() == null ? "" : state.line().source().name();
                if (state.line().ingredientId() == null && selected != null
                        && original != null && !original.trim().isEmpty()
                        && !original.trim().equalsIgnoreCase(selected.getName())) {
                    reason.setText(context.getString(R.string.ac_import_reason_name_replaced,
                            original.trim(), selected.getName()));
                    return;
                }
            }
            ImportRowState.Error error = state.validate(new HashSet<>(), dictionary);
            reason.setText(error == ImportRowState.Error.NONE
                    ? R.string.ac_import_recognized : message(context, error));
        }

        void markInvalid(ImportRowState.Error error) {
            reason.setText(message(root.getContext(), error));
            reason.setTextColor(0xFFB00020);
        }

        private void updateUnit(Context context) {
            int selected = trackedSpinner.getSelectedItemPosition();
            Ingredient ingredient = selected > 0 && selected - 1 < dictionary.size()
                    ? dictionary.get(selected - 1) : null;
            unit.setText(ingredient == null || ingredient.getUnit() == null ? "—"
                    : AcText.unit(context.getResources(), ingredient.getUnit(), ingredient.getName()));
        }

        private void toggleRemoved() {
            removed = !removed;
            root.setAlpha(removed ? 0.45f : 1f);
            mode.setEnabled(!removed);
            trackedSpinner.setEnabled(!removed && state.isTracked());
            quantity.setEnabled(!removed && state.isTracked());
            recipeName.setEnabled(!removed && state.isRecipeOnly());
            amount.setEnabled(!removed && state.isRecipeOnly());
            note.setEnabled(!removed && state.isRecipeOnly());
            action.setText(removed ? R.string.ac_import_restore : R.string.ac_import_remove);
        }
    }

    private static int modeIndex(ImportRowState.Mode mode) {
        return switch (mode) {
            case NONE -> 0;
            case TRACKED -> 1;
            case RECIPE_ONLY -> 2;
        };
    }

    private static ImportRowState.Mode modeFrom(int position) {
        return switch (position) {
            case 1 -> ImportRowState.Mode.TRACKED;
            case 2 -> ImportRowState.Mode.RECIPE_ONLY;
            default -> ImportRowState.Mode.NONE;
        };
    }

    private static int message(Context context, ImportRowState.Error error) {
        return switch (error) {
            case CHOOSE -> R.string.ac_import_reason_choose;
            case INGREDIENT -> R.string.ac_import_reason_no_ingredient;
            case QUANTITY -> R.string.ac_import_reason_invalid_quantity;
            case UNIT -> R.string.ac_import_reason_missing_unit;
            case DUPLICATE -> R.string.ac_import_reason_duplicate;
            case NAME -> R.string.ac_import_reason_recipe_name;
            case AMOUNT, NOTE -> R.string.ac_import_reason_too_long;
            case NONE -> R.string.ac_import_recognized;
        };
    }

    private static int indexOf(List<Ingredient> dictionary, UUID id) {
        if (id == null) return 0;
        for (int i = 0; i < dictionary.size(); i++) if (id.equals(dictionary.get(i).getId())) return i + 1;
        return 0;
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

    private static EditText fieldText(Context context, int hint, String value) {
        EditText field = new EditText(context);
        field.setHint(hint);
        field.setText(value == null ? "" : value);
        field.setTextSize(14);
        field.setSingleLine(true);
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

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private static int dp(float value) {
        return Math.round(value * Resources.getSystem().getDisplayMetrics().density);
    }
}
