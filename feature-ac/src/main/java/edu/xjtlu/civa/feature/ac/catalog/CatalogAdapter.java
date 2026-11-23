package edu.xjtlu.civa.feature.ac.catalog;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import edu.xjtlu.civa.api.model.CatalogRecipe;
import edu.xjtlu.civa.feature.ac.R;
import java.util.ArrayList;
import java.util.List;

/** Grid adapter for the read-only public catalogue. */
final class CatalogAdapter extends RecyclerView.Adapter<CatalogAdapter.Holder> {
    interface OnPick {
        void open(CatalogRecipe recipe);
    }

    private final List<CatalogRecipe> items = new ArrayList<>();
    private final OnPick onPick;

    CatalogAdapter(OnPick onPick) {
        this.onPick = onPick;
    }

    void submit(List<CatalogRecipe> recipes) {
        items.clear();
        if (recipes != null) items.addAll(recipes);
        notifyDataSetChanged();
    }

    int size() { return items.size(); }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.ac_item_catalog, parent, false);
        return new Holder(view);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        CatalogRecipe recipe = items.get(position);
        holder.emoji.setText(recipe.getEmoji() == null ? "🍽️" : recipe.getEmoji());
        holder.name.setText(recipe.getName());
        holder.meta.setText(meta(recipe));
        String tags = recipe.getFlavors().isEmpty() ? recipe.getCuisine() : String.join(" · ", recipe.getFlavors());
        holder.tags.setText(tags);
        holder.tags.setVisibility(tags.isEmpty() ? View.GONE : View.VISIBLE);
        holder.itemView.setOnClickListener(ignored -> onPick.open(recipe));
    }

    private String meta(CatalogRecipe recipe) {
        List<String> parts = new ArrayList<>();
        if (recipe.getCategory() != null && !recipe.getCategory().isEmpty()) parts.add(recipe.getCategory());
        if (recipe.getKcal() != null) parts.add(recipe.getKcal() + " kcal");
        if (recipe.getEstimatedMinutes() != null) parts.add(recipe.getEstimatedMinutes() + " 分钟");
        if (parts.isEmpty() && recipe.getCuisine() != null) parts.add(recipe.getCuisine());
        return String.join(" · ", parts);
    }

    @Override public int getItemCount() { return items.size(); }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView emoji, name, meta, tags;

        Holder(View itemView) {
            super(itemView);
            emoji = itemView.findViewById(R.id.ac_item_emoji);
            name = itemView.findViewById(R.id.ac_item_name);
            meta = itemView.findViewById(R.id.ac_item_meta);
            tags = itemView.findViewById(R.id.ac_item_tags);
        }
    }
}
