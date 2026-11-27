package edu.xjtlu.civa.feature.ac.catalog;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import edu.xjtlu.civa.api.model.Ingredient;
import edu.xjtlu.civa.feature.ac.AcText;
import edu.xjtlu.civa.feature.ac.R;
import java.util.ArrayList;
import java.util.List;

/** Searchable dictionary picker; selection is always a concrete dictionary ingredient. */
final class IngredientPickerDialog {
    interface Listener { void onPick(Ingredient ingredient); }

    private IngredientPickerDialog() { }

    static void show(Context context, List<Ingredient> dictionary, Listener listener) {
        if (dictionary == null || dictionary.isEmpty()) {
            Toast.makeText(context, R.string.ac_ingredient_dictionary_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        List<Ingredient> filtered = new ArrayList<>(dictionary);
        List<String> labels = labels(context, filtered);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                android.R.layout.simple_list_item_1, labels);

        EditText search = new EditText(context);
        search.setHint(R.string.ac_ingredient_search_hint);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));

        TextView empty = new TextView(context);
        empty.setText(R.string.ac_ingredient_no_match);
        empty.setVisibility(View.GONE);
        empty.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));

        ListView list = new ListView(context);
        list.setAdapter(adapter);
        list.setDivider(null);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(context, 16), dp(context, 4), dp(context, 16), dp(context, 4));
        content.addView(search);
        content.addView(empty);
        content.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 300)));

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.ac_ingredient_picker_title)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= filtered.size()) return;
            Ingredient picked = filtered.get(position);
            dialog.dismiss();
            listener.onPick(picked);
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                filtered.clear();
                filtered.addAll(IngredientSearch.filter(dictionary, s.toString()));
                labels.clear();
                labels.addAll(labels(context, filtered));
                adapter.notifyDataSetChanged();
                empty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
                list.setVisibility(filtered.isEmpty() ? View.GONE : View.VISIBLE);
            }
        });
        dialog.show();
    }

    private static List<String> labels(Context context, List<Ingredient> ingredients) {
        List<String> result = new ArrayList<>();
        for (Ingredient ingredient : ingredients) {
            String unit = ingredient.getUnit() == null ? ""
                    : " · " + AcText.unit(context.getResources(), ingredient.getUnit(), ingredient.getName());
            result.add(ingredient.getName() + unit);
        }
        return result;
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
