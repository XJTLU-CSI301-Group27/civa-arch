package edu.xjtlu.civa.feature.ac.catalog;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import edu.xjtlu.civa.api.model.CatalogRecipe;
import edu.xjtlu.civa.api.model.CatalogRecipePage;
import edu.xjtlu.civa.core.data.ApiCallback;
import edu.xjtlu.civa.core.ui.ApiError;
import edu.xjtlu.civa.core.ui.Route;
import edu.xjtlu.civa.feature.ac.AcHost;
import edu.xjtlu.civa.feature.ac.R;
import java.util.ArrayList;
import java.util.List;
import retrofit2.Call;

/** Read-only public recipe catalogue: search, category/high-protein filters, paged grid. */
public final class RecipeCatalogFragment extends Fragment {
    private static final String[] CATEGORIES = {"全部", "家常菜", "减脂", "增肌", "营养", "保持", "食疗", "小吃", "烘焙"};
    private static final int PAGE_SIZE = 60;
    private static final String SEARCH = "ac_catalog_search";

    private final List<CatalogRecipe> loaded = new ArrayList<>();
    private final CatalogAdapter adapter = new CatalogAdapter(this::openDetail);
    private final Handler debounce = new Handler(Looper.getMainLooper());

    private RecyclerView grid;
    private TextView status;
    private MaterialButton more;
    private AcHost host;
    private Call<CatalogRecipePage> call;
    private String query = "", category = "";
    private boolean highProtein;
    private int page;
    private long total;
    private boolean loading, updatingChips;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.ac_fragment_catalog, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        host = (AcHost) requireActivity();
        grid = view.findViewById(R.id.ac_catalog_grid);
        status = view.findViewById(R.id.ac_catalog_status);
        more = view.findViewById(R.id.ac_catalog_more);
        EditText search = view.findViewById(R.id.ac_catalog_search);
        ChipGroup chips = view.findViewById(R.id.ac_catalog_chips);

        int columns = getResources().getConfiguration().screenWidthDp >= 600 ? 3 : 2;
        grid.setLayoutManager(new GridLayoutManager(requireContext(), columns));
        grid.setAdapter(adapter);
        buildChips(chips);
        more.setOnClickListener(ignored -> load(false));
        search.setText(query);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                debounce.removeCallbacksAndMessages(null);
                debounce.postDelayed(() -> {
                    query = s.toString().trim();
                    load(true);
                }, 350);
            }
        });
        load(true);
    }

    private void buildChips(ChipGroup chips) {
        for (String name : CATEGORIES) {
            Chip chip = new Chip(requireContext());
            chip.setText(name);
            chip.setCheckable(true);
            chip.setChecked("全部".equals(name));
            chip.setTag(name);
            chip.setOnCheckedChangeListener((button, checked) -> {
                if (updatingChips) return;
                boolean anyCategory = false;
                for (int i = 0; i < chips.getChildCount(); i++) {
                    View child = chips.getChildAt(i);
                    if (child instanceof Chip chipChild && chipChild.getTag() != null) {
                        if (chipChild.isChecked()) anyCategory = true;
                    }
                }
                updatingChips = true;
                if (checked) {
                    for (int i = 0; i < chips.getChildCount(); i++) {
                        View child = chips.getChildAt(i);
                        if (child instanceof Chip other && other != button && other.getTag() != null) {
                            other.setChecked(false);
                        }
                    }
                } else if (!anyCategory) {
                    updatingChips = false;
                    chip.setChecked(true);
                    return;
                }
                updatingChips = false;
                category = "全部".equals(name) ? "" : name;
                load(true);
            });
            chips.addView(chip);
        }
        Chip protein = new Chip(requireContext());
        protein.setText("高蛋白");
        protein.setCheckable(true);
        protein.setOnCheckedChangeListener((button, checked) -> {
            if (updatingChips) return;
            highProtein = checked;
            load(true);
        });
        chips.addView(protein);
    }

    private void openDetail(CatalogRecipe recipe) {
        host.navigate(Route.RECIPE_DETAIL, recipe.getId() == null ? null : recipe.getId().toString());
    }

    private void load(boolean reset) {
        if (loading) return;
        if (reset) {
            page = 0;
            total = 0;
            loaded.clear();
            adapter.submit(loaded);
        }
        loading = true;
        status.setText(R.string.ac_catalog_loading);
        more.setVisibility(View.GONE);
        int requestPage = page;
        call = host.api().recipeCatalog().listCatalogRecipes(
                query.isEmpty() ? null : query,
                category.isEmpty() ? null : category,
                null,
                highProtein,
                requestPage,
                PAGE_SIZE);
        host.calls().enqueue(call, new ApiCallback<CatalogRecipePage>() {
            @Override public void onSuccess(@Nullable CatalogRecipePage value) {
                loading = false;
                call = null;
                if (value == null || value.getItems() == null) {
                    failed();
                    return;
                }
                loaded.addAll(value.getItems());
                total = value.getTotal() == null ? loaded.size() : value.getTotal();
                adapter.submit(loaded);
                page = requestPage + 1;
                CatalogCache.saveList(requireContext(), value);
                status.setText(getString(R.string.ac_catalog_total, (int) total));
                more.setVisibility(loaded.size() < total ? View.VISIBLE : View.GONE);
                more.setText(getString(R.string.ac_catalog_more, (int) Math.max(0, total - loaded.size())));
            }

            @Override public void onError(ApiError error) {
                loading = false;
                call = null;
                if (loaded.isEmpty()) {
                    CatalogRecipePage cached = CatalogCache.loadList(requireContext());
                    if (cached != null && cached.getItems() != null && !cached.getItems().isEmpty()) {
                        loaded.addAll(cached.getItems());
                        total = cached.getTotal() == null ? loaded.size() : cached.getTotal();
                        adapter.submit(loaded);
                        status.setText(R.string.ac_catalog_offline);
                        return;
                    }
                }
                failed();
            }
        });
    }

    private void failed() {
        status.setText(R.string.ac_catalog_error);
        more.setVisibility(View.GONE);
    }

    @Override public void onDestroyView() {
        debounce.removeCallbacksAndMessages(null);
        if (call != null) {
            call.cancel();
            call = null;
        }
        super.onDestroyView();
    }
}
