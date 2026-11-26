package edu.xjtlu.civa.feature.ac.catalog;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import edu.xjtlu.civa.core.ui.Haptics;
import edu.xjtlu.civa.core.ui.PressBounce;
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
        setHasStableIds(true);
    }

    void submit(List<RecipeCard> cards) {
        List<RecipeCard> next = cards == null ? new ArrayList<>() : new ArrayList<>(cards);
        List<RecipeCard> previous = new ArrayList<>(items);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return previous.size(); }
            @Override public int getNewListSize() { return next.size(); }
            @Override public boolean areItemsTheSame(int oldPos, int newPos) {
                return previous.get(oldPos).kind() == next.get(newPos).kind()
                        && java.util.Objects.equals(previous.get(oldPos).id(), next.get(newPos).id());
            }
            @Override public boolean areContentsTheSame(int oldPos, int newPos) {
                return previous.get(oldPos).equals(next.get(newPos));
            }
        });
        items.clear();
        items.addAll(next);
        diff.dispatchUpdatesTo(this);
    }

    @Override public long getItemId(int position) {
        RecipeCard card = items.get(position);
        return (card.kind().name() + ":" + (card.id() == null ? card.name() : card.id())).hashCode();
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.ac_item_catalog, parent, false);
        PressBounce.bind(view);
        return new Holder(view);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        RecipeCard card = items.get(position);
        holder.emoji.setText(card.emoji() == null ? "🍽️" : card.emoji());
        holder.name.setText(card.name());
        holder.meta.setText(card.metaText(holder.itemView.getResources()));
        String tags = card.tagsText(holder.itemView.getResources());
        holder.tags.setText(tags);
        holder.tags.setVisibility(tags.isEmpty() ? View.GONE : View.VISIBLE);
        holder.itemView.setOnClickListener(ignored -> {
            Haptics.tap(holder.itemView);
            onPick.open(card);
        });
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
