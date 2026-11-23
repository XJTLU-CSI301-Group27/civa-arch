package edu.xjtlu.civa.feature.ac.catalog;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import edu.xjtlu.civa.feature.ac.R;
import java.util.ArrayList;
import java.util.List;

/** Grid adapter over source-agnostic recipe cards. */
final class CatalogAdapter extends RecyclerView.Adapter<CatalogAdapter.Holder> {
    interface OnPick {
        void open(RecipeCard card);
    }

    private final List<RecipeCard> items = new ArrayList<>();
    private final OnPick onPick;

    CatalogAdapter(OnPick onPick) {
        this.onPick = onPick;
    }

    void submit(List<RecipeCard> cards) {
        items.clear();
        if (cards != null) items.addAll(cards);
        notifyDataSetChanged();
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.ac_item_catalog, parent, false);
        return new Holder(view);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        RecipeCard card = items.get(position);
        holder.emoji.setText(card.emoji() == null ? "🍽️" : card.emoji());
        holder.name.setText(card.name());
        holder.meta.setText(card.meta());
        String tags = card.tags() == null ? "" : card.tags();
        holder.tags.setText(tags);
        holder.tags.setVisibility(tags.isEmpty() ? View.GONE : View.VISIBLE);
        holder.itemView.setOnClickListener(ignored -> onPick.open(card));
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
