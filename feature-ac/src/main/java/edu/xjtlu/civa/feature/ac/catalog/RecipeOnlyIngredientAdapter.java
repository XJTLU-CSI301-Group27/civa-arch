package edu.xjtlu.civa.feature.ac.catalog;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import edu.xjtlu.civa.feature.ac.R;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Renders recipe-only rows from IngredientEditorState. */
final class RecipeOnlyIngredientAdapter extends RecyclerView.Adapter<RecipeOnlyIngredientAdapter.Holder> {
    interface Host {
        void onName(long rowId, String text);
        void onAmount(long rowId, String text);
        void onNote(long rowId, String text);
        void onRemove(long rowId);
        void onConvert(long rowId);
    }

    private final Host host;
    private List<IngredientEditorState.Row> rows = List.of();
    private Map<Long, String> errors = Map.of();

    RecipeOnlyIngredientAdapter(Host host) { this.host = host; }

    void submit(List<IngredientEditorState.Row> next, Map<Long, String> nextErrors) {
        rows = next == null ? List.of() : new ArrayList<>(next);
        errors = nextErrors == null ? Map.of() : new HashMap<>(nextErrors);
        notifyDataSetChanged();
    }

    @Override public long getItemId(int position) { return rows.get(position).id; }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.ac_item_ingredient_recipe_only, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        IngredientEditorState.Row row = rows.get(position);
        holder.binding = true;
        if (holder.nameWatcher != null) holder.name.removeTextChangedListener(holder.nameWatcher);
        if (holder.amountWatcher != null) holder.amount.removeTextChangedListener(holder.amountWatcher);
        if (holder.noteWatcher != null) holder.note.removeTextChangedListener(holder.noteWatcher);
        holder.name.setText(row.recipeName);
        holder.amount.setText(row.amountText);
        holder.note.setText(row.note);
        holder.nameWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { if (!holder.binding) host.onName(row.id, s.toString()); }
        };
        holder.amountWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { if (!holder.binding) host.onAmount(row.id, s.toString()); }
        };
        holder.noteWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { if (!holder.binding) host.onNote(row.id, s.toString()); }
        };
        holder.name.addTextChangedListener(holder.nameWatcher);
        holder.amount.addTextChangedListener(holder.amountWatcher);
        holder.note.addTextChangedListener(holder.noteWatcher);
        holder.binding = false;

        String error = errors.get(row.id);
        holder.error.setText(error == null ? "" : error);
        holder.error.setVisibility(error == null ? View.GONE : View.VISIBLE);
        holder.remove.setOnClickListener(v -> host.onRemove(row.id));
        holder.convert.setOnClickListener(v -> host.onConvert(row.id));
    }

    @Override public int getItemCount() { return rows.size(); }

    static final class Holder extends RecyclerView.ViewHolder {
        final EditText name, amount, note;
        final TextView error;
        final MaterialButton remove, convert;
        TextWatcher nameWatcher, amountWatcher, noteWatcher;
        boolean binding;

        Holder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.ac_row_recipe_name);
            amount = itemView.findViewById(R.id.ac_row_amount);
            note = itemView.findViewById(R.id.ac_row_note);
            error = itemView.findViewById(R.id.ac_row_error);
            remove = itemView.findViewById(R.id.ac_row_remove);
            convert = itemView.findViewById(R.id.ac_row_convert);
        }
    }
}
