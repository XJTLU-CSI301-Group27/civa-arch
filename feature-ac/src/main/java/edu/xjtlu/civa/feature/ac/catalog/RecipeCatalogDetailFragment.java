package edu.xjtlu.civa.feature.ac.catalog;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import edu.xjtlu.civa.api.model.CatalogRecipe;
import edu.xjtlu.civa.api.model.CatalogRecipeIngredient;
import edu.xjtlu.civa.core.data.ApiCallback;
import edu.xjtlu.civa.core.ui.ApiError;
import edu.xjtlu.civa.feature.ac.AcHost;
import edu.xjtlu.civa.feature.ac.R;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import retrofit2.Call;

/** Read-only detail of one public recipe; unknown nutrition stays "未提供", never zero. */
public final class RecipeCatalogDetailFragment extends Fragment {
    private static final String ARG_ID = "recipeId";

    private CatalogRecipe recipe;
    private Call<CatalogRecipe> call;

    public static RecipeCatalogDetailFragment create(@Nullable String recipeId) {
        RecipeCatalogDetailFragment fragment = new RecipeCatalogDetailFragment();
        Bundle args = new Bundle();
        args.putString(ARG_ID, recipeId);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.ac_fragment_catalog_detail, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        String id = getArguments() == null ? null : getArguments().getString(ARG_ID);
        if (id == null) {
            render(view, null);
            return;
        }
        CatalogRecipe cached = CatalogCache.loadDetail(requireContext(), id);
        if (cached != null) render(view, cached);
        AcHost host = (AcHost) requireActivity();
        call = host.api().recipeCatalog().getCatalogRecipe(UUID.fromString(id));
        host.calls().enqueue(call, new ApiCallback<CatalogRecipe>() {
            @Override public void onSuccess(@Nullable CatalogRecipe value) {
                call = null;
                if (value != null) {
                    CatalogCache.saveDetail(requireContext(), value);
                    render(view, value);
                }
            }

            @Override public void onError(ApiError error) {
                call = null;
            }
        });
    }

    private void render(View view, CatalogRecipe value) {
        recipe = value;
        TextView emoji = view.findViewById(R.id.ac_detail_emoji);
        TextView name = view.findViewById(R.id.ac_detail_name);
        TextView meta = view.findViewById(R.id.ac_detail_meta);
        TextView tags = view.findViewById(R.id.ac_detail_tags);
        TextView description = view.findViewById(R.id.ac_detail_description);
        LinearLayout ingredients = view.findViewById(R.id.ac_detail_ingredients);
        LinearLayout steps = view.findViewById(R.id.ac_detail_steps);
        LinearLayout nutrition = view.findViewById(R.id.ac_detail_nutrition);
        if (value == null) {
            name.setText(R.string.ac_catalog_missing);
            return;
        }
        emoji.setText(value.getEmoji() == null ? "🍽️" : value.getEmoji());
        name.setText(value.getName());
        List<String> metaParts = new ArrayList<>();
        if (has(value.getCategory())) metaParts.add(value.getCategory());
        if (has(value.getCuisine())) metaParts.add(value.getCuisine());
        if (value.getKcal() != null) metaParts.add(value.getKcal() + " kcal / 份");
        if (value.getEstimatedMinutes() != null) metaParts.add(value.getEstimatedMinutes() + " 分钟");
        meta.setText(String.join(" · ", metaParts));
        List<String> tagParts = new ArrayList<>(value.getMethods());
        tagParts.addAll(value.getFlavors());
        tagParts.addAll(value.getMealTypes());
        tags.setText(String.join(" · ", tagParts));
        tags.setVisibility(tagParts.isEmpty() ? View.GONE : View.VISIBLE);
        description.setText(has(value.getDescription()) ? value.getDescription() : getString(R.string.ac_catalog_no_description));
        description.setVisibility(has(value.getDescription()) ? View.VISIBLE : View.GONE);

        ingredients.removeAllViews();
        for (CatalogRecipeIngredient item : value.getIngredients()) {
            String amount = joinAmount(item);
            TextView row = line(getString(R.string.ac_catalog_ingredient, item.getName(), amount), 15, R.color.ac_catalog_ink);
            ingredients.addView(row);
        }
        if (value.getIngredients().isEmpty()) ingredients.addView(line(getString(R.string.ac_catalog_missing), 15, R.color.ac_catalog_muted));

        steps.removeAllViews();
        int number = 1;
        for (String step : value.getSteps()) {
            steps.addView(line(number++ + ". " + step, 15, R.color.ac_catalog_ink));
        }
        if (value.getSteps().isEmpty()) steps.addView(line(getString(R.string.ac_catalog_missing), 15, R.color.ac_catalog_muted));

        nutrition.removeAllViews();
        boolean any = false;
        if (value.getProtein() != null) { nutrition.addView(nutritionRow(R.string.ac_catalog_protein, value.getProtein())); any = true; }
        if (value.getCarbs() != null) { nutrition.addView(nutritionRow(R.string.ac_catalog_carbs, value.getCarbs())); any = true; }
        if (value.getFat() != null) { nutrition.addView(nutritionRow(R.string.ac_catalog_fat, value.getFat())); any = true; }
        if (value.getProteinPer100g() != null) { nutrition.addView(nutritionRow(R.string.ac_catalog_protein100, value.getProteinPer100g())); any = true; }
        if (value.getCarbsPer100g() != null) { nutrition.addView(nutritionRow(R.string.ac_catalog_carbs100, value.getCarbsPer100g())); any = true; }
        if (value.getFatPer100g() != null) { nutrition.addView(nutritionRow(R.string.ac_catalog_fat100, value.getFatPer100g())); any = true; }
        if (!any) nutrition.addView(line(getString(R.string.ac_catalog_no_nutrition), 15, R.color.ac_catalog_muted));
    }

    private TextView nutritionRow(int label, String value) {
        return line(getString(label) + "：" + value, 15, R.color.ac_catalog_ink);
    }

    private TextView line(String text, int size, int color) {
        TextView view = new TextView(requireContext());
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(requireContext().getColor(color));
        view.setPadding(0, 6, 0, 6);
        view.setLineSpacing(0f, 1.25f);
        return view;
    }

    private String joinAmount(CatalogRecipeIngredient item) {
        List<String> parts = new ArrayList<>();
        if (has(item.getQuantityPrefix())) parts.add(item.getQuantityPrefix());
        if (has(item.getQuantity())) parts.add(item.getQuantity());
        if (has(item.getUnit())) parts.add(item.getUnit());
        if (has(item.getNote())) parts.add("（" + item.getNote() + "）");
        return String.join(" ", parts);
    }

    private static boolean has(String value) { return value != null && !value.trim().isEmpty(); }

    @Override public void onDestroyView() {
        if (call != null) {
            call.cancel();
            call = null;
        }
        super.onDestroyView();
    }
}
