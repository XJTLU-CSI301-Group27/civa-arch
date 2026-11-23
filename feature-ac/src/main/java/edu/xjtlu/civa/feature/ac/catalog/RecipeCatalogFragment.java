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
import edu.xjtlu.civa.api.model.CatalogRecipePage;
import edu.xjtlu.civa.api.model.PersonalRecipePage;
import edu.xjtlu.civa.api.model.RecipeFavoritePage;
import edu.xjtlu.civa.core.data.ApiCallback;
import edu.xjtlu.civa.core.ui.ApiError;
import edu.xjtlu.civa.core.ui.Route;
import edu.xjtlu.civa.feature.ac.AcHost;
import edu.xjtlu.civa.feature.ac.R;
import java.util.ArrayList;
import java.util.List;
import retrofit2.Call;

/** Recipe tab: public catalogue, my personal recipes, and my "want to eat" list. */
public final class RecipeCatalogFragment extends Fragment {
    private static final String[] CATEGORIES = {"全部", "家常菜", "减脂", "增肌", "营养", "保持", "食疗", "小吃", "烘焙"};
    private static final int PAGE_SIZE = 60;

    private enum Source { PUBLIC, PERSONAL, HOUSEHOLD, FAVORITES }

    private final List<RecipeCard> loaded = new ArrayList<>();
    private final RecipeCard.RecipeCards cards = RecipeCard.cards();
    private final CatalogAdapter adapter = new CatalogAdapter(this::open);
    private final Handler debounce = new Handler(Looper.getMainLooper());

    private AcHost host;
    private RecyclerView grid;
    private TextView status;
    private MaterialButton more, create;
    private EditText search;
    private ChipGroup chips, sources;
    private Call<?> call;
    private Source source = Source.PUBLIC;
    private String query = "", category = "";
    private boolean highProtein, loading, updatingChips;
    private int page;
    private long total;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.ac_fragment_catalog, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        host = (AcHost) requireActivity();
        grid = view.findViewById(R.id.ac_catalog_grid);
        status = view.findViewById(R.id.ac_catalog_status);
        more = view.findViewById(R.id.ac_catalog_more);
        create = view.findViewById(R.id.ac_catalog_create);
        search = view.findViewById(R.id.ac_catalog_search);
        chips = view.findViewById(R.id.ac_catalog_chips);
        sources = view.findViewById(R.id.ac_catalog_sources);

        int columns = getResources().getConfiguration().screenWidthDp >= 600 ? 3 : 2;
        grid.setLayoutManager(new GridLayoutManager(requireContext(), columns));
        grid.setAdapter(adapter);
        more.setOnClickListener(ignored -> load(false));
        create.setOnClickListener(ignored -> host.navigate(Route.RECIPE_EDIT, source == Source.HOUSEHOLD ? "hnew" : "new"));
        buildSources();
        buildCategoryChips();
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

    private void buildSources() {
        String[][] labels = {{"公共", "PUBLIC"}, {"我的", "PERSONAL"}, {"家庭", "HOUSEHOLD"}, {"想吃", "FAVORITES"}};
        for (String[] entry : labels) {
            Chip chip = new Chip(requireContext());
            chip.setText(entry[0]);
            chip.setCheckable(true);
            chip.setTag(entry[1]);
            chip.setChecked("PUBLIC".equals(entry[1]));
            chip.setOnCheckedChangeListener((button, checked) -> {
                if (updatingChips || !checked) return;
                updatingChips = true;
                for (int i = 0; i < sources.getChildCount(); i++) {
                    View child = sources.getChildAt(i);
                    if (child instanceof Chip other && other != button) other.setChecked(false);
                }
                updatingChips = false;
                source = Source.valueOf(entry[1]);
                search.setVisibility(source == Source.FAVORITES ? View.GONE : View.VISIBLE);
                chips.setVisibility(source == Source.FAVORITES ? View.GONE : View.VISIBLE);
                create.setVisibility(source == Source.PERSONAL || source == Source.HOUSEHOLD ? View.VISIBLE : View.GONE);
                load(true);
            });
            sources.addView(chip);
        }
    }

    private void buildCategoryChips() {
        for (String name : CATEGORIES) {
            Chip chip = new Chip(requireContext());
            chip.setText(name);
            chip.setCheckable(true);
            chip.setTag(name);
            chip.setChecked("全部".equals(name));
            chip.setOnCheckedChangeListener((button, checked) -> {
                if (updatingChips) return;
                boolean any = false;
                for (int i = 0; i < chips.getChildCount(); i++) {
                    View child = chips.getChildAt(i);
                    if (child instanceof Chip chipChild && chipChild.getTag() != null && chipChild.isChecked()) any = true;
                }
                updatingChips = true;
                if (checked) {
                    for (int i = 0; i < chips.getChildCount(); i++) {
                        View child = chips.getChildAt(i);
                        if (child instanceof Chip other && other != button && other.getTag() != null) other.setChecked(false);
                    }
                } else if (!any) {
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

    private void open(RecipeCard card) {
        if (source == Source.PERSONAL) {
            host.navigate(Route.RECIPE_DETAIL, RecipeCard.personalKey(card.id()));
        } else if (source == Source.HOUSEHOLD) {
            host.navigate(Route.RECIPE_DETAIL, RecipeCard.householdKey(card.id()));
        } else {
            host.navigate(Route.RECIPE_DETAIL, card.id());
        }
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
        if (source == Source.PUBLIC) {
            Call<CatalogRecipePage> request = host.api().recipeCatalog().listCatalogRecipes(
                    query.isEmpty() ? null : query,
                    category.isEmpty() ? null : category, null, highProtein, requestPage, PAGE_SIZE);
            call = request;
            host.calls().enqueue(request, new ApiCallback<CatalogRecipePage>() {
                @Override public void onSuccess(@Nullable CatalogRecipePage value) {
                    loading = false;
                    if (value == null || value.getItems() == null) { failed(); return; }
                    loaded.addAll(cards.fromCatalog(value.getItems()));
                    total = value.getTotal() == null ? loaded.size() : value.getTotal();
                    if (requestPage == 0) CatalogCache.saveList(requireContext(), value);
                    render(requestPage);
                }

                @Override public void onError(ApiError error) {
                    loading = false;
                    if (requestPage == 0 && loaded.isEmpty()) {
                        CatalogRecipePage cached = CatalogCache.loadList(requireContext());
                        if (cached != null && cached.getItems() != null && !cached.getItems().isEmpty()) {
                            loaded.addAll(cards.fromCatalog(cached.getItems()));
                            total = cached.getTotal() == null ? loaded.size() : cached.getTotal();
                            adapter.submit(loaded);
                            status.setText(R.string.ac_catalog_offline);
                            return;
                        }
                    }
                    failed();
                }
            });
        } else if (source == Source.HOUSEHOLD) {
            java.util.UUID home = host.sessions() == null ? null : uuidOrNull(host.sessions().currentHouseholdId());
            if (home == null) {
                loading = false;
                failed();
                return;
            }
            Call<edu.xjtlu.civa.api.model.RecipePage> request = host.api().recipe().listRecipes(
                    home, null, null, null, false, requestPage, PAGE_SIZE);
            call = request;
            host.calls().enqueue(request, new ApiCallback<edu.xjtlu.civa.api.model.RecipePage>() {
                @Override public void onSuccess(@Nullable edu.xjtlu.civa.api.model.RecipePage value) {
                    loading = false;
                    if (value == null || value.getItems() == null) { failed(); return; }
                    loaded.addAll(cards.fromHousehold(value.getItems()));
                    total = value.getTotal() == null ? loaded.size() : value.getTotal();
                    render(requestPage);
                }

                @Override public void onError(ApiError error) {
                    loading = false;
                    failed();
                }
            });
        } else if (source == Source.PERSONAL) {
            Call<PersonalRecipePage> request = host.api().personalRecipes().listPersonalRecipes(
                    query.isEmpty() ? null : query, category.isEmpty() ? null : category, false, requestPage, PAGE_SIZE);
            call = request;
            host.calls().enqueue(request, new ApiCallback<PersonalRecipePage>() {
                @Override public void onSuccess(@Nullable PersonalRecipePage value) {
                    loading = false;
                    if (value == null || value.getItems() == null) { failed(); return; }
                    loaded.addAll(cards.fromPersonal(value.getItems()));
                    total = value.getTotal() == null ? loaded.size() : value.getTotal();
                    render(requestPage);
                }

                @Override public void onError(ApiError error) {
                    loading = false;
                    failed();
                }
            });
        } else {
            Call<RecipeFavoritePage> request = host.api().recipeFavorites().listRecipeFavorites(null, requestPage, PAGE_SIZE);
            call = request;
            host.calls().enqueue(request, new ApiCallback<RecipeFavoritePage>() {
                @Override public void onSuccess(@Nullable RecipeFavoritePage value) {
                    loading = false;
                    if (value == null || value.getItems() == null) { failed(); return; }
                    loaded.addAll(cards.fromFavorites(value.getItems()));
                    total = value.getTotal() == null ? loaded.size() : value.getTotal();
                    render(requestPage);
                }

                @Override public void onError(ApiError error) {
                    loading = false;
                    failed();
                }
            });
        }
    }

    private void render(int requestPage) {
        adapter.submit(loaded);
        page = requestPage + 1;
        status.setText(getString(R.string.ac_catalog_total, (int) total));
        more.setVisibility(loaded.size() < total ? View.VISIBLE : View.GONE);
        more.setText(getString(R.string.ac_catalog_more, (int) Math.max(0, total - loaded.size())));
    }

    private void failed() {
        status.setText(R.string.ac_catalog_error);
        more.setVisibility(View.GONE);
    }

    private static java.util.UUID uuidOrNull(String value) {
        if (value == null || value.isEmpty()) return null;
        try { return java.util.UUID.fromString(value); } catch (IllegalArgumentException invalid) { return null; }
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
