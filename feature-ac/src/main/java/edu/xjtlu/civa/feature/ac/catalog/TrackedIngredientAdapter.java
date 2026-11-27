package edu.xjtlu.civa.feature.ac.catalog;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import edu.xjtlu.civa.api.model.Ingredient;
import edu.xjtlu.civa.feature.ac.AcText;
import edu.xjtlu.civa.feature.ac.R;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Renders tracked rows from IngredientEditorState; no business data lives in a ViewHolder. */
final class TrackedIngredientAdapter extends RecyclerView.Adapter<TrackedIngredientAdapter.Holder> {
    interface Host {
        List<Ingredient> ingredients();
        void onTrackedSelected(long rowId, Ingredient ingredient);
        void onQuantity(long rowId, String text);
        void onRemove(long rowId);
        void onConvert(long rowId);
    }

    private final Host host;
    private List<IngredientEditorState.Row> rows = List.of();
    private Map<Long, String> errors = Map.of();

    TrackedIngredientAdapter(Host host) { this.host = host; }

    void submit(List<IngredientEditorState.Row> next, Map<Long, String> nextErrors) {
        rows = next == null ? List.of() : new ArrayList<>(next);
        errors = nextErrors == null ? Map.of() : new HashMap<>(nextErrors);
        notifyDataSetChanged();
    }

    @Override public long getItemId(int position) { return rows.get(position).id; }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.ac_item_ingredient_tracked, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        IngredientEditorState.Row row = rows.get(position);
        holder.binding = true;
        if (holder.quantityWatcher != null) holder.quantity.removeTextChangedListener(holder.quantityWatcher);
        holder.quantity.setText(row.quantity);
        boolean legacy = row.legacy();
        holder.quantity.setEnabled(!legacy);
        holder.remove.setVisibility(legacy ? View.GONE : View.VISIBLE);
        holder.legacy.setVisibility(legacy ? View.VISIBLE : View.GONE);

        List<Ingredient> ingredients = host.ingredients();
        List<String> choices = new ArrayList<>();
        choices.add(holder.itemView.getContext().getString(R.string.ac_household_recipe_row_name));
        for (Ingredient ingredient : ingredients) choices.add(ingredient.getName());
        ArrayAdapter<String> adapter = new ArrayAdapter<>(holder.itemView.getContext(),
                android.R.layout.simple_spinner_item, choices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        holder.name.setAdapter(adapter);
        holder.name.setEnabled(!legacy);
        int selected = 0;
        for (int i = 0; i < ingredients.size(); i++) {
            if (ingredients.get(i).getId().equals(row.ingredientId)) {
                selected = i + 1;
                break;
            }
        }
        holder.name.setSelection(selected, false);
        holder.unit.setText(row.unit == null ? "—" : AcText.unit(holder.itemView.getResources(),
                row.unit, row.trackedName));
        holder.name.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (holder.binding || position <= 0 || position > ingredients.size()) return;
                Ingredient ingredient = ingredients.get(position - 1);
                host.onTrackedSelected(row.id, ingredient);
                holder.unit.setText(ingredient.getUnit() == null ? "—"
                        : AcText.unit(holder.itemView.getResources(), ingredient.getUnit(), ingredient.getName()));
            }

            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        holder.quantityWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                if (!holder.binding) host.onQuantity(row.id, s.toString());
            }
        };
        holder.quantity.addTextChangedListener(holder.quantityWatcher);
        holder.binding = false;

        String error = errors.get(row.id);
        holder.error.setText(error == null ? "" : error);
        holder.error.setVisibility(error == null ? View.GONE : View.VISIBLE);
        holder.remove.setOnClickListener(v -> host.onRemove(row.id));
        holder.convert.setOnClickListener(v -> host.onConvert(row.id));
    }

    @Override public int getItemCount() { return rows.size(); }

    static final class Holder extends RecyclerView.ViewHolder {
        final Spinner name;
        final EditText quantity;
        final TextView unit, legacy, error;
        final MaterialButton remove, convert;
        TextWatcher quantityWatcher;
        boolean binding;

        Holder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.ac_row_name);
            quantity = itemView.findViewById(R.id.ac_row_quantity);
            unit = itemView.findViewById(R.id.ac_row_unit);
            legacy = itemView.findViewById(R.id.ac_row_legacy);
            error = itemView.findViewById(R.id.ac_row_error);
            remove = itemView.findViewById(R.id.ac_row_remove);
            convert = itemView.findViewById(R.id.ac_row_convert);
        }
    }
}
