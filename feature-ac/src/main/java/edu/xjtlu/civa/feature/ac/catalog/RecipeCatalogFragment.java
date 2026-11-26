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
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import edu.xjtlu.civa.api.model.CatalogRecipePage;
import edu.xjtlu.civa.api.model.Ingredient;
import edu.xjtlu.civa.api.model.IngredientPage;
import edu.xjtlu.civa.api.model.MatchEntry;
import edu.xjtlu.civa.api.model.MatchRequest;
import edu.xjtlu.civa.api.model.PersonalRecipePage;
import edu.xjtlu.civa.api.model.Recipe;
import edu.xjtlu.civa.api.model.RecipeFavoritePage;
import edu.xjtlu.civa.api.model.RecipeMatch;
import edu.xjtlu.civa.core.data.ApiCallback;
import edu.xjtlu.civa.core.ui.ApiError;
import edu.xjtlu.civa.core.ui.Route;
import edu.xjtlu.civa.feature.ac.AcHost;
import edu.xjtlu.civa.feature.ac.AcText;
import edu.xjtlu.civa.feature.ac.R;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import retrofit2.Call;

/** Recipe tab: public catalogue, my personal recipes, and my "want to eat" list. */
public final class RecipeCatalogFragment extends Fragment {
    // i18n: API filter values (data sent to the server); the chips show localized labels.
    private static final String[] CATEGORIES = {"全部", "家常菜", "减脂", "增肌", "营养", "保持", "食疗", "小吃", "烘焙"};
    private static final int[] CATEGORY_LABELS = {
            R.string.ac_l10n_catalog_category_all,
            R.string.ac_l10n_catalog_category_home,
            R.string.ac_l10n_catalog_category_low_fat,
            R.string.ac_l10n_catalog_category_muscle,
            R.string.ac_l10n_catalog_category_nutrition,
            R.string.ac_l10n_catalog_category_maintain,
            R.string.ac_l10n_catalog_category_therapy,
            R.string.ac_l10n_catalog_category_snack,
            R.string.ac_l10n_catalog_category_baking
    };
    private static final int PAGE_SIZE = 60;

    private enum Source { PUBLIC, PERSONAL, HOUSEHOLD, FAVORITES }

    private final List<RecipeCard> loaded = new ArrayList<>();
    private final RecipeCard.RecipeCards cards = RecipeCard.cards();
    private final CatalogAdapter adapter = new CatalogAdapter(this::open);
    private final Handler debounce = new Handler(Looper.getMainLooper());
    private final Map<UUID, Recipe> householdRecipes = new LinkedHashMap<>();
    private final Map<String, RecipeStockPresenter.StockView> stockViews = new LinkedHashMap<>();
    private final Map<String, String> stockTexts = new LinkedHashMap<>();
    private Map<UUID, Ingredient> ingredientDictionary = Map.of();
    private long stockToken;

    private final RecipeViewCallbacks viewCallbacks = new RecipeViewCallbacks();

    private AcHost host;
    private RecyclerView grid;
    private TextView status;
    private MaterialButton more, create;
    private EditText search;
    private ChipGroup chips, sources;
    private View empty, catClearance;
    private TextView emptyText;
    private Call<?> call;
    private Source source = Source.PUBLIC;
    private String query = "", category = "";
    private boolean highProtein, loading, updatingChips;
    private int page;
    private long total;
    // Survives view recreation (navigator replaces fragments) and rotation.
    private static Source lastSource = Source.PUBLIC;
    private static String lastQuery = "", lastCategory = "";
    private static boolean lastHighProtein;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.ac_fragment_catalog, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        viewCallbacks.startView();
        loading = false;
        host = (AcHost) requireActivity();
        grid = view.findViewById(R.id.ac_catalog_grid);
        status = view.findViewById(R.id.ac_catalog_status);
        more = view.findViewById(R.id.ac_catalog_more);
        create = view.findViewById(R.id.ac_catalog_create);
        search = view.findViewById(R.id.ac_catalog_search);
        chips = view.findViewById(R.id.ac_catalog_chips);
        sources = view.findViewById(R.id.ac_catalog_sources);
        empty = view.findViewById(R.id.ac_catalog_empty);
        catClearance = view.findViewById(R.id.ac_catalog_cat_clearance);
        emptyText = view.findViewById(R.id.ac_catalog_empty_text);

        int columns = getResources().getConfiguration().screenWidthDp >= 600 ? 3 : 2;
        source = lastSource;
        query = lastQuery;
        category = lastCategory;
        highProtein = lastHighProtein;
        search.setText(query);
        create.setVisibility(source == Source.PERSONAL || source == Source.HOUSEHOLD
                ? View.VISIBLE : View.INVISIBLE);
        search.setVisibility(source == Source.FAVORITES ? View.GONE : View.VISIBLE);
        chips.setVisibility(source == Source.FAVORITES ? View.GONE : View.VISIBLE);
        catClearance.setVisibility(source == Source.FAVORITES ? View.VISIBLE : View.GONE);
        grid.setLayoutManager(new GridLayoutManager(requireContext(), columns));
        DefaultItemAnimator itemAnimator = new DefaultItemAnimator();
        itemAnimator.setAddDuration(180L);
        itemAnimator.setRemoveDuration(160L);
        itemAnimator.setMoveDuration(180L);
        itemAnimator.setChangeDuration(180L);
        grid.setItemAnimator(itemAnimator);
        grid.setAdapter(adapter);
        more.setOnClickListener(ignored -> load(false));
        create.setOnClickListener(ignored -> {
            if (source == Source.HOUSEHOLD && host.sessions().currentHouseholdId() == null) host.navigate(Route.HOUSEHOLD, null);
            else host.navigate(Route.RECIPE_EDIT, source == Source.HOUSEHOLD ? "hnew" : "new");
        });
        sources.setSingleSelection(true);
        sources.setSelectionRequired(true);
        chips.setSingleSelection(true);
        chips.setSelectionRequired(true);
        buildSources();
        buildCategoryChips();
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                debounce.removeCallbacksAndMessages(null);
                debounce.postDelayed(() -> {
                    query = s.toString().trim();
                    lastQuery = query;
                    load(true);
                }, 350);
            }
        });
        load(true);
    }

    private void buildSources() {
        String[] values = {"PUBLIC", "PERSONAL", "HOUSEHOLD", "FAVORITES"};
        int[] labels = {
                R.string.ac_l10n_catalog_source_public,
                R.string.ac_l10n_catalog_source_personal,
                R.string.ac_l10n_catalog_source_household,
                R.string.ac_l10n_card_want_to_eat
        };
        for (int index = 0; index < values.length; index++) {
            String value = values[index];
            Chip chip = new Chip(requireContext());
            chip.setText(labels[index]);
            chip.setTextSize(12);
            chip.setCheckable(true);
            chip.setTag(value);
            chip.setChecked(value.equals(source.name()));
            chip.setOnCheckedChangeListener((button, checked) -> {
                if (updatingChips || !checked) return;
                source = Source.valueOf(value);
                lastSource = source;
                search.setVisibility(source == Source.FAVORITES ? View.GONE : View.VISIBLE);
                chips.setVisibility(source == Source.FAVORITES ? View.GONE : View.VISIBLE);
                catClearance.setVisibility(source == Source.FAVORITES ? View.VISIBLE : View.GONE);
                create.setVisibility(source == Source.PERSONAL || source == Source.HOUSEHOLD ? View.VISIBLE : View.INVISIBLE);
                load(true);
            });
            sources.addView(chip);
        }
    }

    private void buildCategoryChips() {
        for (int index = 0; index < CATEGORIES.length; index++) {
            String name = CATEGORIES[index];
            Chip chip = new Chip(requireContext());
            chip.setText(CATEGORY_LABELS[index]);
            chip.setCheckable(true);
            chip.setTag(name);
            chip.setChecked(index == 0);
            final int categoryIndex = index;
            chip.setOnCheckedChangeListener((button, checked) -> {
                if (updatingChips || !checked) return;
                category = categoryIndex == 0 ? "" : name;
                lastCategory = category;
                load(true);
            });
            chips.addView(chip);
        }
        Chip protein = new Chip(requireContext());
        protein.setText(R.string.ac_l10n_catalog_high_protein);
        protein.setCheckable(true);
        protein.setChecked(highProtein);
        protein.setOnCheckedChangeListener((button, checked) -> {
            if (updatingChips) return;
            highProtein = checked;
            lastHighProtein = highProtein;
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
        if (reset) {
            // A newly selected source/search owns a fresh callback generation.
            viewCallbacks.startView();
            stockToken++;
            householdRecipes.clear();
            stockViews.clear();
            stockTexts.clear();
            if (call != null) { call.cancel(); call = null; }
            loading = false;
        }
        if (loading) return;
        empty.setVisibility(View.GONE);
        if (reset) {
            page = 0;
            total = 0;
            loaded.clear();
            adapter.submit(loaded, stockTexts);
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
            host.calls().enqueue(request, viewCallbacks.bind(new ApiCallback<CatalogRecipePage>() {
                @Override public void onSuccess(@Nullable CatalogRecipePage value) {
                    if (!isAdded()) return;
                    loading = false;
                    if (value == null || value.getItems() == null) { failed(); return; }
                    loaded.addAll(cards.fromCatalog(value.getItems()));
                    total = value.getTotal() == null ? loaded.size() : value.getTotal();
                    if (requestPage == 0) CatalogCache.saveList(requireContext(), value);
                    render(requestPage);
                }

                @Override public void onError(ApiError error) {
                    if (!isAdded()) return;
                    loading = false;
                    if (requestPage == 0 && loaded.isEmpty()) {
                        CatalogRecipePage cached = CatalogCache.loadList(requireContext());
                        if (cached != null && cached.getItems() != null && !cached.getItems().isEmpty()) {
                            loaded.addAll(cards.fromCatalog(cached.getItems()));
                            total = cached.getTotal() == null ? loaded.size() : cached.getTotal();
                            adapter.submit(loaded, stockTexts);
                            status.setText(R.string.ac_catalog_offline);
                            return;
                        }
                    }
                    failed();
                }
            }));
        } else if (source == Source.HOUSEHOLD) {
            java.util.UUID home = host.sessions() == null ? null : uuidOrNull(host.sessions().currentHouseholdId());
            if (home == null) {
                loading = false;
                status.setText(R.string.ac_catalog_join_first);
                return;
            }
            Call<edu.xjtlu.civa.api.model.RecipePage> request = host.api().recipe().listRecipes(
                    home, null, null, null, false, requestPage, PAGE_SIZE);
            call = request;
            host.calls().enqueue(request, viewCallbacks.bind(new ApiCallback<edu.xjtlu.civa.api.model.RecipePage>() {
                @Override public void onSuccess(@Nullable edu.xjtlu.civa.api.model.RecipePage value) {
                    if (!isAdded()) return;
                    loading = false;
                    if (value == null || value.getItems() == null) { failed(); return; }
                    for (Recipe recipe : value.getItems()) {
                        if (recipe != null && recipe.getId() != null) householdRecipes.put(recipe.getId(), recipe);
                    }
                    loaded.addAll(cards.fromHousehold(value.getItems()));
                    total = value.getTotal() == null ? loaded.size() : value.getTotal();
                    render(requestPage);
                    requestHouseholdStock(home, ++stockToken);
                }

                @Override public void onError(ApiError error) {
                    if (!isAdded()) return;
                    loading = false;
                    failed();
                }
            }));
        } else if (source == Source.PERSONAL) {
            Call<PersonalRecipePage> request = host.api().personalRecipes().listPersonalRecipes(
                    query.isEmpty() ? null : query, category.isEmpty() ? null : category, false, requestPage, PAGE_SIZE);
            call = request;
            host.calls().enqueue(request, viewCallbacks.bind(new ApiCallback<PersonalRecipePage>() {
                @Override public void onSuccess(@Nullable PersonalRecipePage value) {
                    if (!isAdded()) return;
                    loading = false;
                    if (value == null || value.getItems() == null) { failed(); return; }
                    loaded.addAll(cards.fromPersonal(value.getItems()));
                    total = value.getTotal() == null ? loaded.size() : value.getTotal();
                    render(requestPage);
                }

                @Override public void onError(ApiError error) {
                    if (!isAdded()) return;
                    loading = false;
                    failed();
                }
            }));
        } else {
            Call<RecipeFavoritePage> request = host.api().recipeFavorites().listRecipeFavorites(null, requestPage, PAGE_SIZE);
            call = request;
            host.calls().enqueue(request, viewCallbacks.bind(new ApiCallback<RecipeFavoritePage>() {
                @Override public void onSuccess(@Nullable RecipeFavoritePage value) {
                    if (!isAdded()) return;
                    loading = false;
                    if (value == null || value.getItems() == null) { failed(); return; }
                    loaded.addAll(cards.fromFavorites(value.getItems()));
                    total = value.getTotal() == null ? loaded.size() : value.getTotal();
                    render(requestPage);
                }

                @Override public void onError(ApiError error) {
                    if (!isAdded()) return;
                    loading = false;
                    failed();
                }
            }));
        }
    }

    private void render(int requestPage) {
        adapter.submit(loaded, stockTexts);
        emptyText.setText(source == Source.FAVORITES ? R.string.ac_catalog_empty_favorites : R.string.ac_catalog_empty_mine);
        empty.setVisibility(loaded.isEmpty() && source != Source.PUBLIC ? View.VISIBLE : View.GONE);
        page = requestPage + 1;
        status.setText(getString(R.string.ac_catalog_total, (int) total));
        more.setVisibility(loaded.size() < total ? View.VISIBLE : View.GONE);
        more.setText(getString(R.string.ac_catalog_more, (int) Math.max(0, total - loaded.size())));
    }

    private void failed() {
        status.setText(R.string.ac_catalog_error);
        more.setVisibility(View.GONE);
    }

    /**
     * Household cards get a stock status without blocking the list. Requests are generation-guarded
     * so a late response for an old home/source/search can never paint the new page.
     */
    private void requestHouseholdStock(UUID home, long token) {
        if (!stockValid(home, token)) return;
        for (RecipeCard card : loaded) {
            if (card.kind() == RecipeCard.Kind.HOUSEHOLD) {
                stockTexts.put(card.id(), getString(R.string.ac_stock_checking));
            }
        }
        adapter.submit(loaded, stockTexts);
        if (ingredientDictionary.isEmpty()) {
            loadDictionary(home, 0, new ArrayList<>(), token);
        } else {
            matchStock(home, token);
        }
    }

    private void loadDictionary(UUID home, int page, List<Ingredient> collected, long token) {
        if (!stockValid(home, token)) return;
        host.calls().enqueue(host.api().inventory().listIngredients(null, page, 100),
                new ApiCallback<IngredientPage>() {
                    @Override public void onSuccess(@Nullable IngredientPage value) {
                        if (!stockValid(home, token)) return;
                        if (value == null || value.getItems() == null) { stockUnavailable(home, token); return; }
                        collected.addAll(value.getItems());
                        long total = value.getTotal() == null ? collected.size() : value.getTotal();
                        if (collected.size() < total) {
                            if (!value.getItems().isEmpty()) {
                                loadDictionary(home, page + 1, collected, token);
                            } else {
                                // A short page before the advertised total would make units/names
                                // unreliable; degrade to UNKNOWN instead of guessing.
                                stockUnavailable(home, token);
                            }
                            return;
                        }
                        Map<UUID, Ingredient> dictionary = new LinkedHashMap<>();
                        for (Ingredient ingredient : collected) {
                            if (ingredient != null && ingredient.getId() != null) dictionary.put(ingredient.getId(), ingredient);
                        }
                        ingredientDictionary = dictionary;
                        matchStock(home, token);
                    }

                    @Override public void onError(ApiError error) {
                        if (stockValid(home, token)) stockUnavailable(home, token);
                    }
                });
    }

    private void matchStock(UUID home, long token) {
        if (!stockValid(home, token)) return;
        List<Recipe> recipes = new ArrayList<>(householdRecipes.values());
        for (int start = 0; start < recipes.size(); start += 100) {
            List<Recipe> chunk = new ArrayList<>(recipes.subList(start, Math.min(start + 100, recipes.size())));
            List<MatchEntry> entries = new ArrayList<>();
            for (Recipe recipe : chunk) {
                entries.add(new MatchEntry().recipeId(recipe.getId()).servings(RecipeStockPresenter.servingsFor(recipe)));
            }
            host.calls().enqueue(host.api().inventory().matchRecipes(home, new MatchRequest().entries(entries)),
                    new ApiCallback<List<RecipeMatch>>() {
                        @Override public void onSuccess(@Nullable List<RecipeMatch> value) {
                            if (!stockValid(home, token)) return;
                            Map<UUID, RecipeMatch> byRecipe = new LinkedHashMap<>();
                            if (value != null) {
                                for (RecipeMatch match : value) {
                                    if (match != null && match.getRecipeId() != null) byRecipe.put(match.getRecipeId(), match);
                                }
                            }
                            for (Recipe recipe : chunk) {
                                RecipeMatch match = byRecipe.get(recipe.getId());
                                RecipeStockPresenter.StockView view = RecipeStockPresenter.present(
                                        recipe, match, ingredientDictionary, RecipeStockPresenter.servingsFor(recipe));
                                putStock(recipe.getId(), view);
                            }
                            applyStockResult();
                        }

                        @Override public void onError(ApiError error) {
                            if (!stockValid(home, token)) return;
                            for (Recipe recipe : chunk) {
                                putStock(recipe.getId(), RecipeStockPresenter.StockView.unknown(
                                        RecipeStockPresenter.servingsFor(recipe)));
                            }
                            applyStockResult();
                        }
                    });
        }
    }

    private void stockUnavailable(UUID home, long token) {
        if (!stockValid(home, token)) return;
        for (RecipeCard card : loaded) {
            if (card.kind() == RecipeCard.Kind.HOUSEHOLD) {
                Recipe recipe = householdRecipes.get(uuidOrNull(card.id()));
                putStock(uuidOrNull(card.id()), RecipeStockPresenter.StockView.unknown(
                        recipe == null ? "1" : RecipeStockPresenter.servingsFor(recipe)));
            }
        }
        applyStockResult();
    }

    private void putStock(UUID recipeId, RecipeStockPresenter.StockView view) {
        if (recipeId == null) return;
        stockViews.put(recipeId.toString(), view);
        stockTexts.put(recipeId.toString(), stockText(view));
    }

    private String stockText(RecipeStockPresenter.StockView view) {
        if (view == null) return getString(R.string.ac_stock_unknown);
        if (view.state() == RecipeStockPresenter.State.UNKNOWN) return getString(R.string.ac_stock_unknown);
        if (view.state() == RecipeStockPresenter.State.ENOUGH) {
            return view.tracked() ? getString(R.string.ac_stock_enough, view.servings())
                    : getString(R.string.ac_stock_no_tracking);
        }
        List<String> parts = new ArrayList<>();
        int shown = Math.min(2, view.missing().size());
        for (int index = 0; index < shown; index++) {
            RecipeStockPresenter.Missing missing = view.missing().get(index);
            parts.add(missing.name() + " " + missing.quantity() + " "
                    + AcText.unit(getResources(), missing.unit(), missing.name()));
        }
        String summary = AcText.join(getResources(), parts);
        if (view.missing().size() > shown) {
            summary += " " + getString(R.string.ac_stock_more, view.missing().size() - shown);
        }
        return getString(R.string.ac_stock_missing, summary, view.servings());
    }

    private void applyStockResult() {
        loaded.sort((left, right) -> Integer.compare(stockRank(left), stockRank(right)));
        adapter.submit(loaded, stockTexts);
    }

    private int stockRank(RecipeCard card) {
        if (card.kind() != RecipeCard.Kind.HOUSEHOLD) return 0;
        return RecipeStockPresenter.rank(stockViews.get(card.id()));
    }

    private boolean stockValid(UUID home, long token) {
        return token == stockToken && isAdded() && grid != null && source == Source.HOUSEHOLD
                && home != null && home.equals(currentHome());
    }

    private UUID currentHome() {
        return host == null || host.sessions() == null ? null
                : uuidOrNull(host.sessions().currentHouseholdId());
    }

    private static java.util.UUID uuidOrNull(String value) {
        if (value == null || value.isEmpty()) return null;
        try { return java.util.UUID.fromString(value); } catch (IllegalArgumentException invalid) { return null; }
    }

    @Override public void onDestroyView() {
        // Invalidate before cancel(): cancellation can deliver an error callback.
        viewCallbacks.endView();
        stockToken++;
        debounce.removeCallbacksAndMessages(null);
        if (call != null) {
            call.cancel();
            call = null;
        }
        loading = false;
        grid.setAdapter(null);
        grid = null;
        status = null;
        more = null;
        create = null;
        search = null;
        chips = null;
        sources = null;
        empty = null;
        emptyText = null;
        catClearance = null;
        host = null;
        super.onDestroyView();
    }
}
