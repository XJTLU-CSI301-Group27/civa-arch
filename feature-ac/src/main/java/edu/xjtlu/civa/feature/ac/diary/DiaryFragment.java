package edu.xjtlu.civa.feature.ac.diary;

import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import edu.xjtlu.civa.api.model.CookingEvent;
import edu.xjtlu.civa.api.model.CookingEventPage;
import edu.xjtlu.civa.api.model.CookingPreview;
import edu.xjtlu.civa.api.model.CookingPreviewRequest;
import edu.xjtlu.civa.api.model.ChangeReason;
import edu.xjtlu.civa.api.model.ConfirmCookingRequest;
import edu.xjtlu.civa.api.model.InventoryBatch;
import edu.xjtlu.civa.api.model.InventoryBatchPage;
import edu.xjtlu.civa.api.model.InventoryChangeRequest;
import edu.xjtlu.civa.api.model.NutritionBasis;
import edu.xjtlu.civa.api.model.NutritionEstimate;
import edu.xjtlu.civa.api.model.NutritionEstimateRequest;
import edu.xjtlu.civa.api.model.CreateMealEntryRequest;
import edu.xjtlu.civa.api.model.DaySummary;
import edu.xjtlu.civa.api.model.Ingredient;
import edu.xjtlu.civa.api.model.IngredientPage;
import edu.xjtlu.civa.api.model.MatchLine;
import edu.xjtlu.civa.api.model.Recipe;
import edu.xjtlu.civa.api.model.RecipePage;
import edu.xjtlu.civa.api.model.EntryMealType;
import edu.xjtlu.civa.api.model.KcalBasis;
import edu.xjtlu.civa.api.model.MealEntry;
import edu.xjtlu.civa.api.model.MealEntryPage;
import edu.xjtlu.civa.api.model.PeriodSummary;
import edu.xjtlu.civa.api.model.UpdateMealEntryRequest;
import edu.xjtlu.civa.core.data.ApiCallback;
import edu.xjtlu.civa.core.data.PlannedDish;
import edu.xjtlu.civa.core.ui.ApiError;
import edu.xjtlu.civa.feature.ac.AcHost;
import edu.xjtlu.civa.feature.ac.AcText;
import edu.xjtlu.civa.feature.ac.R;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Food diary: month calendar of actual intake, per-day entries and household cooking history. */
public final class DiaryFragment extends Fragment {

    private AcHost host;
    private YearMonth month = YearMonth.now();
    private LocalDate selected = LocalDate.now();
    private final Map<String, DaySummary> marker = new LinkedHashMap<>();
    private final Map<EntryMealType, List<MealEntry>> entries = new LinkedHashMap<>();
    private LinearLayout calendar, entryList, cookingList, planList;
    private TextView planTitle;
    private final Map<UUID, String> ingredientNames = new LinkedHashMap<>();
    private List<PlannedDish> planned = List.of();
    private boolean ingredientNamesLoaded;
    private TextView monthLabel, monthSummary, dayTitle, daySummary;
    private boolean loading;

    public DiaryFragment() { super(R.layout.ac_fragment_diary); }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        host = (AcHost) requireActivity();
        calendar = view.findViewById(R.id.ac_diary_calendar);
        entryList = view.findViewById(R.id.ac_diary_entries);
        cookingList = view.findViewById(R.id.ac_diary_cooking);
        planList = view.findViewById(R.id.ac_diary_plan);
        planTitle = view.findViewById(R.id.ac_diary_plan_title);
        monthLabel = view.findViewById(R.id.ac_diary_month);
        monthSummary = view.findViewById(R.id.ac_diary_month_summary);
        dayTitle = view.findViewById(R.id.ac_diary_day_title);
        daySummary = view.findViewById(R.id.ac_diary_day_summary);
        view.findViewById(R.id.ac_diary_previous).setOnClickListener(ignored -> shiftMonth(-1));
        view.findViewById(R.id.ac_diary_next).setOnClickListener(ignored -> shiftMonth(1));
        view.findViewById(R.id.ac_diary_today).setOnClickListener(ignored -> {
            month = YearMonth.from(LocalDate.now());
            selected = LocalDate.now();
            loadMonth();
        });
        view.findViewById(R.id.ac_diary_add).setOnClickListener(ignored -> chooseScenario());
        loadMonth();
        loadCooking();
    }

    private void shiftMonth(int delta) {
        month = month.plusMonths(delta);
        selected = month.atDay(Math.min(selected.getDayOfMonth(), month.lengthOfMonth()));
        loadMonth();
    }

    /** Date patterns and weekday names follow the app language, not a fixed locale. */
    private DateTimeFormatter monthTitleFormat() {
        return DateTimeFormatter.ofPattern(
                getString(R.string.ac_l10n_date_month), AcText.locale(getResources()));
    }

    private DateTimeFormatter dayTitleFormat() {
        return DateTimeFormatter.ofPattern(
                getString(R.string.ac_l10n_date_day_week), AcText.locale(getResources()));
    }

    private void loadMonth() {
        if (!isAdded()) return;
        loading = true;
        renderCalendar();
        monthLabel.setText(monthTitleFormat().format(month));
        dayTitle.setText(dayTitleFormat().format(selected));
        daySummary.setText("");
        host.calls().enqueue(host.api().records().getMealSummary(month.atDay(1).toString(),
                        month.atEndOfMonth().toString()),
                new ApiCallback<PeriodSummary>() {
                    @Override public void onSuccess(@Nullable PeriodSummary value) {
                        if (!isAdded()) return;
                        loading = false;
                        marker.clear();
                        if (value != null && value.getDays() != null) {
                            for (DaySummary day : value.getDays()) marker.put(day.getDate(), day);
                        }
                        String known = value == null ? "0" : value.getKnownKcal();
                        int unknown = value == null || value.getUnknownCount() == null ? 0 : value.getUnknownCount();
                        monthSummary.setText(getString(R.string.ac_diary_month_summary, known, unknown));
                        renderCalendar();
                        loadDay();
                    }

                    @Override public void onError(ApiError error) {
                        if (!isAdded()) return;
                        loading = false;
                        monthSummary.setText(R.string.ac_diary_load_error);
                        loadDay();
                    }
                });
    }

    private void loadDay() {
        if (!isAdded()) return;
        String day = selected.toString();
        DaySummary summary = marker.get(day);
        dayTitle.setText(dayTitleFormat().format(selected));
        daySummary.setText(summary == null ? getString(R.string.ac_diary_no_records)
                : getString(R.string.ac_diary_day_summary, summary.getKnownKcal(),
                        summary.getUnknownCount() == null ? 0 : summary.getUnknownCount()));
        loadPlan();
        host.calls().enqueue(host.api().records().listMealEntries(day, day, 0, 100),
                new ApiCallback<MealEntryPage>() {
                    @Override public void onSuccess(@Nullable MealEntryPage value) {
                        if (!isAdded()) return;
                        entries.clear();
                        if (value != null && value.getItems() != null) {
                            for (MealEntry entry : value.getItems()) {
                                entries.computeIfAbsent(entry.getMealType(), k -> new ArrayList<>()).add(entry);
                            }
                        }
                        renderEntries();
                        renderPlan();
                    }

                    @Override public void onError(ApiError error) {
                        if (!isAdded()) return;
                        entries.clear();
                        renderEntries();
                        renderPlan();
                    }
                });
    }

    private void loadPlan() {
        if (!isAdded()) return;
        try {
            planned = host.planFor(selected);
        } catch (RuntimeException unavailable) {
            planned = List.of();
        }
        renderPlan();
    }

    private void renderPlan() {
        if (planList == null) return;
        planList.removeAllViews();
        planTitle.setVisibility(planned.isEmpty() ? View.GONE : View.VISIBLE);
        for (PlannedDish dish : planned) planList.addView(planRow(dish, isRecorded(dish)));
    }

    private boolean isRecorded(PlannedDish dish) {
        for (List<MealEntry> rows : entries.values()) {
            for (MealEntry entry : rows) {
                if (entry.getName() != null && entry.getName().trim().equalsIgnoreCase(dish.name().trim())
                        && entry.getMealType() == plannedMeal(dish.meal())) return true;
            }
        }
        return false;
    }

    private View planRow(PlannedDish dish, boolean recorded) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(12), dp(8));
        row.setBackgroundResource(R.drawable.ac_catalog_card);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(6);
        row.setLayoutParams(params);
        String text = getString(R.string.ac_diary_plan_row, mealLabel(plannedMeal(dish.meal())), dish.name());
        if (recorded) text = text + getString(R.string.ac_diary_plan_recorded_mark);
        TextView label = line(text, 14, R.color.ac_catalog_ink);
        label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(label);
        com.google.android.material.button.MaterialButton eaten = new com.google.android.material.button.MaterialButton(
                requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        eaten.setText(R.string.ac_diary_plan_eaten);
        eaten.setTextSize(12);
        eaten.setInsetTop(0);
        eaten.setInsetBottom(0);
        if (recorded) {
            eaten.setVisibility(View.GONE);
        } else {
            eaten.setOnClickListener(ignored -> confirmPlannedDish(dish));
        }
        row.addView(eaten);
        return row;
    }

    private static EntryMealType plannedMeal(String meal) {
        try {
            return EntryMealType.fromValue(meal);
        } catch (RuntimeException invalid) {
            return EntryMealType.LUNCH;
        }
    }

    /** "Ate this planned dish": deduct the fridge and record the meal, warning about missing items. */
    private void confirmPlannedDish(PlannedDish dish) {
        if (!isAdded()) return;
        String homeText = host.sessions() == null ? null : host.sessions().currentHouseholdId();
        if (homeText == null) {
            recordPlannedMeal(dish, null, null, false);
            return;
        }
        UUID home = UUID.fromString(homeText);
        host.calls().enqueue(host.api().recipe().listRecipes(home, dish.name(), null, null, false, 0, 20),
                new ApiCallback<RecipePage>() {
                    @Override public void onSuccess(@Nullable RecipePage value) {
                        if (!isAdded()) return;
                        Recipe match = null;
                        if (value != null && value.getItems() != null) {
                            for (Recipe row : value.getItems()) {
                                if (row.getName() != null
                                        && row.getName().trim().equalsIgnoreCase(dish.name().trim())) {
                                    match = row;
                                    break;
                                }
                            }
                        }
                        if (match == null) {
                            offerRecordOnly(dish, R.string.ac_diary_plan_no_recipe);
                            return;
                        }
                        loadRecipeAndPreview(home, dish, match.getId());
                    }

                    @Override public void onError(ApiError error) {
                        if (!isAdded()) return;
                        offerRecordOnly(dish, R.string.ac_diary_plan_failed);
                    }
                });
    }

    private void loadRecipeAndPreview(UUID home, PlannedDish dish, UUID recipeId) {
        host.calls().enqueue(host.api().recipe().getRecipe(home, recipeId), new ApiCallback<Recipe>() {
            @Override public void onSuccess(@Nullable Recipe recipe) {
                if (!isAdded() || recipe == null) return;
                String servings = recipe.getYieldServings() == null || recipe.getYieldServings().isEmpty()
                        ? "2" : recipe.getYieldServings();
                CookingPreviewRequest request = new CookingPreviewRequest()
                        .recipeId(recipe.getId()).servings(servings);
                host.calls().enqueue(host.api().records().previewCooking(home, request),
                        new ApiCallback<CookingPreview>() {
                            @Override public void onSuccess(@Nullable CookingPreview preview) {
                                if (!isAdded() || preview == null) return;
                                if (preview.getShortages() == null || preview.getShortages().isEmpty()) {
                                    confirmPlannedCook(home, dish, recipe, preview);
                                } else {
                                    ensureIngredientNames(() -> showShortageDialog(home, dish, recipe, preview));
                                }
                            }

                            @Override public void onError(ApiError error) {
                                if (!isAdded()) return;
                                offerRecordOnly(dish, R.string.ac_diary_plan_failed);
                            }
                        });
            }

            @Override public void onError(ApiError error) {
                if (!isAdded()) return;
                offerRecordOnly(dish, R.string.ac_diary_plan_failed);
            }
        });
    }

    private void confirmPlannedCook(UUID home, PlannedDish dish, Recipe recipe, CookingPreview preview) {
        ConfirmCookingRequest request = new ConfirmCookingRequest()
                .recipeId(recipe.getId())
                .recipeVersion(preview.getRecipeVersion())
                .servings(preview.getServings())
                .allocations(preview.getAllocations())
                .cookedAt(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                        .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now()));
        host.calls().enqueue(host.api().records().confirmCooking(home, UUID.randomUUID(), request),
                new ApiCallback<CookingEvent>() {
                    @Override public void onSuccess(@Nullable CookingEvent value) {
                        if (!isAdded()) return;
                        recordPlannedMeal(dish, recipe, value == null ? null : value.getId(), true);
                    }

                    @Override public void onError(ApiError error) {
                        if (!isAdded()) return;
                        offerRecordOnly(dish, R.string.ac_diary_plan_failed);
                    }
                });
    }

    private void showShortageDialog(UUID home, PlannedDish dish, Recipe recipe, CookingPreview preview) {
        if (!isAdded()) return;
        List<String> lines = new ArrayList<>();
        for (MatchLine shortage : preview.getShortages()) {
            String name = ingredientNames.getOrDefault(shortage.getIngredientId(),
                    getString(R.string.ac_l10n_ingredient_fallback));
            lines.add(getString(R.string.ac_diary_plan_shortage_row, name,
                    shortage.getRequired() == null ? "?" : shortage.getRequired(),
                    shortage.getAvailable() == null ? "0" : shortage.getAvailable()));
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ac_diary_plan_shortage_title)
                .setMessage(getString(R.string.ac_diary_plan_shortage,
                        AcText.join(getResources(), lines)))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.ac_diary_plan_record_only, (dialog, which) ->
                        recordPlannedMeal(dish, recipe, null, false))
                .show();
    }

    private void offerRecordOnly(PlannedDish dish, int message) {
        if (!isAdded()) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.ac_diary_plan_record_only, (dialog, which) ->
                        recordPlannedMeal(dish, null, null, false))
                .show();
    }

    private void recordPlannedMeal(PlannedDish dish, @Nullable Recipe recipe, @Nullable UUID cookingEventId,
                                    boolean deducted) {
        if (!isAdded()) return;
        BigDecimal basisKcal = null;
        if (recipe != null) {
            try {
                BigDecimal total = recipe.getTotalKcal() == null || recipe.getTotalKcal().isEmpty()
                        ? null : new BigDecimal(recipe.getTotalKcal());
                BigDecimal yield = recipe.getYieldServings() == null || recipe.getYieldServings().isEmpty()
                        ? null : new BigDecimal(recipe.getYieldServings());
                if (total != null && yield != null && yield.signum() > 0) {
                    basisKcal = total.divide(yield, 3, RoundingMode.HALF_UP);
                }
            } catch (NumberFormatException ignored) {
                basisKcal = null;
            }
        }
        KcalBasis basis = basisKcal == null ? KcalBasis.UNKNOWN : KcalBasis.SERVING;
        CreateMealEntryRequest request = new CreateMealEntryRequest()
                .date(selected.toString())
                .mealType(plannedMeal(dish.meal()))
                .name(dish.name())
                .amount("1")
                .basis(basis)
                .basisKcal(basisKcal == null ? null : plain(basisKcal))
                .note(getString(R.string.ac_diary_plan_title))
                .cookingEventId(cookingEventId);
        host.calls().enqueue(host.api().records().createMealEntry(UUID.randomUUID(), request),
                new ApiCallback<MealEntry>() {
                    @Override public void onSuccess(@Nullable MealEntry value) {
                        if (!isAdded()) return;
                        toast(deducted ? R.string.ac_diary_plan_done : R.string.ac_diary_plan_recorded);
                        loadMonth();
                    }

                    @Override public void onError(ApiError error) {
                        if (!isAdded()) return;
                        toast(R.string.ac_diary_plan_failed);
                    }
                });
    }

    private void ensureIngredientNames(Runnable then) {
        if (ingredientNamesLoaded) { then.run(); return; }
        loadIngredientNames(0, then);
    }

    private void loadIngredientNames(int page, Runnable then) {
        host.calls().enqueue(host.api().inventory().listIngredients(null, page, 100),
                new ApiCallback<IngredientPage>() {
                    @Override public void onSuccess(@Nullable IngredientPage value) {
                        if (!isAdded()) return;
                        if (value == null || value.getItems() == null) {
                            ingredientNamesLoaded = true;
                            then.run();
                            return;
                        }
                        for (Ingredient ingredient : value.getItems()) {
                            ingredientNames.put(ingredient.getId(), ingredient.getName());
                        }
                        long total = value.getTotal() == null ? ingredientNames.size() : value.getTotal();
                        if (ingredientNames.size() < total && page < 20) loadIngredientNames(page + 1, then);
                        else {
                            ingredientNamesLoaded = true;
                            then.run();
                        }
                    }

                    @Override public void onError(ApiError error) {
                        if (!isAdded()) return;
                        ingredientNamesLoaded = true;
                        then.run();
                    }
                });
    }

    private void loadCooking() {
        if (!isAdded()) return;
        String home = host.sessions() == null ? null : host.sessions().currentHouseholdId();
        if (home == null) {
            cookingList.removeAllViews();
            cookingList.addView(line(getString(R.string.ac_diary_cooking_no_home), 13, R.color.ac_catalog_muted));
            return;
        }
        host.calls().enqueue(host.api().records().listCookingEvents(UUID.fromString(home), null, null, null, 0, 8),
                new ApiCallback<CookingEventPage>() {
                    @Override public void onSuccess(@Nullable CookingEventPage value) {
                        if (!isAdded()) return;
                        cookingList.removeAllViews();
                        List<CookingEvent> events = value == null || value.getItems() == null
                                ? List.of() : value.getItems();
                        if (events.isEmpty()) {
                            cookingList.addView(line(getString(R.string.ac_diary_cooking_empty), 13,
                                    R.color.ac_catalog_muted));
                            return;
                        }
                        for (CookingEvent event : events) cookingList.addView(cookingRow(event));
                    }

                    @Override public void onError(ApiError error) {
                        if (!isAdded()) return;
                        cookingList.removeAllViews();
                        cookingList.addView(line(getString(R.string.ac_diary_load_error), 13,
                                R.color.ac_catalog_muted));
                    }
                });
    }

    private View cookingRow(CookingEvent event) {
        String name = event.getRecipe() == null ? "?" : event.getRecipe().getRecipeName();
        String date = event.getCookedAt() == null ? "" : event.getCookedAt().substring(5, 10).replace('-', '/');
        TextView row = line(getString(R.string.ac_diary_cooking_row, date, name, event.getServings()), 14,
                R.color.ac_catalog_ink);
        row.setPadding(0, dp(8), 0, dp(8));
        row.setOnClickListener(ignored -> editEntry(null, event));
        return row;
    }

    private void renderCalendar() {
        if (calendar == null) return;
        calendar.removeAllViews();
        LinearLayout header = new LinearLayout(requireContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        Locale locale = AcText.locale(getResources());
        for (int weekday = 1; weekday <= 7; weekday++)
            header.addView(
                    cell(
                            DayOfWeek.of(weekday).getDisplayName(TextStyle.NARROW, locale),
                            false,
                            false,
                            false,
                            0));
        calendar.addView(header);
        int offset = month.atDay(1).getDayOfWeek().getValue() - 1;
        LinearLayout week = newWeek();
        for (int i = 0; i < offset; i++) week.addView(cell("", false, false, false, 0));
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            LocalDate date = month.atDay(day);
            boolean selectedDay = date.equals(selected);
            boolean recorded = marker.containsKey(date.toString());
            week.addView(cell(Integer.toString(day), true, selectedDay, recorded, day));
            if (week.getChildCount() == 7) {
                calendar.addView(week);
                week = newWeek();
            }
        }
        while (week.getChildCount() > 0 && week.getChildCount() < 7) week.addView(cell("", false, false, false, 0));
        if (week.getChildCount() > 0) calendar.addView(week);
    }

    private LinearLayout newWeek() {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private TextView cell(String text, boolean active, boolean selectedDay, boolean recorded, int day) {
        TextView view = new TextView(requireContext());
        view.setText(recorded && active && !selectedDay ? "· " + text + " ·" : text);
        view.setGravity(android.view.Gravity.CENTER);
        view.setTextSize(14);
        view.setPadding(0, dp(10), 0, dp(10));
        view.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        view.setTextColor(requireContext().getColor(selectedDay ? R.color.ac_catalog_ink : R.color.ac_catalog_ink));
        if (selectedDay) view.setBackgroundResource(R.drawable.ac_catalog_card);
        view.setTypeface(null, recorded ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        if (active && day > 0) {
            final LocalDate date = month.atDay(day);
            view.setOnClickListener(ignored -> {
                selected = date;
                renderCalendar();
                loadDay();
            });
        }
        return view;
    }

    private void renderEntries() {
        if (entryList == null) return;
        entryList.removeAllViews();
        if (entries.isEmpty()) {
            entryList.addView(line(getString(R.string.ac_diary_empty), 14, R.color.ac_catalog_muted));
            return;
        }
        for (EntryMealType meal : EntryMealType.values()) {
            List<MealEntry> rows = entries.get(meal);
            if (rows == null || rows.isEmpty()) continue;
            entryList.addView(section(mealLabel(meal)));
            for (MealEntry entry : rows) entryList.addView(entryRow(entry));
        }
    }

    private View entryRow(MealEntry entry) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackgroundResource(R.drawable.ac_catalog_card);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(6);
        row.setLayoutParams(params);
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout left = new LinearLayout(requireContext());
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView name = new TextView(requireContext());
        name.setText(entry.getName());
        name.setTextSize(15);
        name.setTextColor(requireContext().getColor(R.color.ac_catalog_ink));
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        left.addView(name);
        TextView detail = new TextView(requireContext());
        String amount = entry.getAmount() == null ? "" : entry.getAmount();
        detail.setText(getString(R.string.ac_diary_entry_detail, amount, basisLabel(entry.getBasis()),
                entry.getNote() == null || entry.getNote().isEmpty() ? "" : "· " + entry.getNote()));
        detail.setTextSize(12);
        detail.setTextColor(requireContext().getColor(R.color.ac_catalog_muted));
        left.addView(detail);
        row.addView(left);
        TextView kcal = new TextView(requireContext());
        kcal.setText(entry.getTotalKcal() == null ? getString(R.string.ac_diary_unknown)
                : getString(R.string.ac_diary_kcal, entry.getTotalKcal()));
        kcal.setTextSize(14);
        kcal.setTextColor(requireContext().getColor(R.color.ac_catalog_accent));
        kcal.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(kcal);
        row.setOnClickListener(ignored -> editEntry(entry, null));
        return row;
    }

    /** First decision: eating at home (fridge ingredients) or outside (free input). */
    private void chooseScenario() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ac_diary_new)
                .setItems(new String[]{getString(R.string.ac_diary_at_home),
                        getString(R.string.ac_diary_outside)}, (dialog, which) -> {
                    if (which == 0) addHomeMeal();
                    else editEntry(null, null);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** At home: only fridge batches can be picked, and the used quantities are consumed. */
    private void addHomeMeal() {
        if (!isAdded()) return;
        String homeText = host.sessions() == null ? null : host.sessions().currentHouseholdId();
        if (homeText == null) {
            toast(R.string.ac_diary_cooking_no_home);
            return;
        }
        UUID home = UUID.fromString(homeText);
        ensureIngredientNames(() -> host.calls().enqueue(
                host.api().inventory().listInventoryBatches(home, null, null, 0, 100),
                new ApiCallback<InventoryBatchPage>() {
                    @Override public void onSuccess(@Nullable InventoryBatchPage value) {
                        if (!isAdded()) return;
                        List<InventoryBatch> batches = value == null || value.getItems() == null
                                ? List.of() : value.getItems();
                        if (batches.isEmpty()) {
                            toast(R.string.ac_diary_home_empty_fridge);
                            return;
                        }
                        showHomeMealDialog(home, batches);
                    }

                    @Override public void onError(ApiError error) {
                        if (!isAdded()) return;
                        toast(R.string.ac_diary_load_error);
                    }
                }));
    }

    private void showHomeMealDialog(UUID home, List<InventoryBatch> batches) {
        EditText name = field(getString(R.string.ac_diary_home_name_hint));
        Map<UUID, EditText> amounts = new LinkedHashMap<>();
        Map<UUID, InventoryBatch> byId = new LinkedHashMap<>();
        LinearLayout list = new LinearLayout(requireContext());
        list.setOrientation(LinearLayout.VERTICAL);
        list.addView(line(getString(R.string.ac_diary_home_ingredients_hint), 12, R.color.ac_catalog_muted));
        for (InventoryBatch batch : batches) {
            String ingredient = ingredientNames.getOrDefault(batch.getIngredientId(),
                    getString(R.string.ac_l10n_ingredient_fallback));
            String label = ingredient + " · " + batch.getQuantity() + " "
                    + AcText.unit(getResources(), batch.getUnit(), ingredient)
                    + (batch.getExpiresOn() == null ? ""
                            : getString(R.string.ac_l10n_diary_expiry_suffix, batch.getExpiresOn()));
            com.google.android.material.checkbox.MaterialCheckBox check =
                    new com.google.android.material.checkbox.MaterialCheckBox(requireContext());
            check.setText(label);
            check.setTextSize(13);
            EditText amount = new EditText(requireContext());
            amount.setHint(R.string.ac_diary_home_amount_hint);
            amount.setTextSize(13);
            amount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            amount.setBackgroundResource(R.drawable.ac_catalog_search);
            amount.setPadding(dp(10), dp(6), dp(10), dp(6));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(4);
            amount.setLayoutParams(params);
            amount.setVisibility(View.GONE);
            check.setOnCheckedChangeListener((button, checked) -> {
                amount.setVisibility(checked ? View.VISIBLE : View.GONE);
                if (checked && amount.getText().toString().trim().isEmpty()) {
                    amount.setText(batch.getQuantity());
                }
            });
            list.addView(check);
            list.addView(amount);
            amounts.put(batch.getId(), amount);
            byId.put(batch.getId(), batch);
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(requireContext());
        scroll.addView(list);

        EntryMealType[] meals = EntryMealType.values();
        Map<EntryMealType, MaterialButton> mealButtons = new LinkedHashMap<>();
        LinearLayout mealRow = new LinearLayout(requireContext());
        mealRow.setOrientation(LinearLayout.HORIZONTAL);
        for (EntryMealType meal : meals) {
            MaterialButton button = new MaterialButton(requireContext(), null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            button.setText(mealLabel(meal));
            button.setTextSize(12);
            button.setInsetTop(0);
            button.setInsetBottom(0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            params.setMarginEnd(dp(4));
            mealRow.addView(button, params);
            mealButtons.put(meal, button);
            button.setOnClickListener(ignored -> select(mealButtons, meal));
        }
        select(mealButtons, EntryMealType.LUNCH);

        LinearLayout rootView = new LinearLayout(requireContext());
        rootView.setOrientation(LinearLayout.VERTICAL);
        rootView.setPadding(dp(20), dp(6), dp(20), dp(6));
        rootView.addView(mealRow);
        rootView.addView(name);
        rootView.addView(scroll);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ac_diary_at_home)
                .setView(rootView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.ac_diary_save, (dialog, which) -> {
                    String dish = name.getText().toString().trim();
                    if (dish.isEmpty()) {
                        toast(R.string.ac_diary_invalid);
                        return;
                    }
                    List<InventoryBatch> picked = new ArrayList<>();
                    List<BigDecimal> used = new ArrayList<>();
                    List<String> lines = new ArrayList<>();
                    for (Map.Entry<UUID, InventoryBatch> entry : byId.entrySet()) {
                        InventoryBatch batch = entry.getValue();
                        String text = amounts.get(batch.getId()).getText().toString().trim();
                        if (!amounts.get(batch.getId()).isShown() || text.isEmpty()) continue;
                        BigDecimal amount = decimal(text);
                        if (amount == null || amount.signum() <= 0) {
                            toast(R.string.ac_diary_invalid);
                            return;
                        }
                        BigDecimal available = decimal(batch.getQuantity());
                        if (available == null || amount.compareTo(available) > 0) {
                            toast(R.string.ac_diary_home_too_much);
                            return;
                        }
                        picked.add(batch);
                        used.add(amount);
                        lines.add(ingredientNames.getOrDefault(batch.getIngredientId(),
                                        getString(R.string.ac_l10n_ingredient_fallback))
                                + " " + plain(amount) + " " + batch.getUnit().getValue());
                    }
                    if (picked.isEmpty()) {
                        toast(R.string.ac_diary_home_pick);
                        return;
                    }
                    consumeThenRecord(home, selected(mealButtons, meals), dish, picked, used, lines);
                })
                .show();
    }

    private void consumeThenRecord(UUID home, EntryMealType meal, String dish, List<InventoryBatch> batches,
                                   List<BigDecimal> used, List<String> lines) {
        consumeBatch(home, meal, dish, batches, used, lines, 0);
    }

    private void consumeBatch(UUID home, EntryMealType meal, String dish, List<InventoryBatch> batches,
                              List<BigDecimal> used, List<String> lines, int index) {
        if (!isAdded()) return;
        if (index >= batches.size()) {
            estimateAndRecord(meal, dish, lines);
            return;
        }
        InventoryBatch batch = batches.get(index);
        BigDecimal before = decimal(batch.getQuantity());
        BigDecimal after = (before == null ? BigDecimal.ZERO : before).subtract(used.get(index));
        InventoryChangeRequest request = new InventoryChangeRequest()
                .afterQuantity(plain(after.max(BigDecimal.ZERO)))
                .reason(ChangeReason.CONSUME)
                .expectedVersion(batch.getVersion());
        final int next = index + 1;
        host.calls().enqueue(host.api().inventory().changeInventory(home, batch.getId(),
                        UUID.randomUUID(), request), new ApiCallback<edu.xjtlu.civa.api.model.InventoryMovement>() {
                    @Override public void onSuccess(@Nullable edu.xjtlu.civa.api.model.InventoryMovement value) {
                        if (!isAdded()) return;
                        consumeBatch(home, meal, dish, batches, used, lines, next);
                    }

                    @Override public void onError(ApiError error) {
                        if (!isAdded()) return;
                        toast(R.string.ac_diary_home_consume_failed);
                    }
                });
    }

    /** Same idea as the today page: ask the server AI for a per-100g estimate, unknown if unavailable. */
    private void estimateAndRecord(EntryMealType meal, String dish, List<String> lines) {
        NutritionEstimateRequest request = new NutritionEstimateRequest().name(dish).ingredients(lines);
        host.calls().enqueue(host.api().nutrition().estimateNutrition(request),
                new ApiCallback<NutritionEstimate>() {
                    @Override public void onSuccess(@Nullable NutritionEstimate value) {
                        if (!isAdded()) return;
                        BigDecimal kcal = null;
                        if (value != null && value.getBasis() == NutritionBasis.PER_100_G
                                && value.getEnergyKcal() != null) {
                            BigDecimal grams = BigDecimal.ZERO;
                            for (String line : lines) {
                                String[] parts = line.split(" ");
                                if (parts.length >= 2) {
                                    BigDecimal amount = decimal(parts[1]);
                                    if (amount != null) grams = grams.add(amount);
                                }
                            }
                            if (grams.signum() > 0) {
                                kcal = value.getEnergyKcal()
                                        .multiply(grams)
                                        .divide(BigDecimal.valueOf(100), 3, RoundingMode.HALF_UP);
                            }
                        }
                        recordHomeMeal(meal, dish, lines, kcal);
                    }

                    @Override public void onError(ApiError error) {
                        if (!isAdded()) return;
                        recordHomeMeal(meal, dish, lines, null);
                    }
                });
    }

    private void recordHomeMeal(EntryMealType meal, String dish, List<String> lines,
                                @Nullable BigDecimal kcal) {
        if (!isAdded()) return;
        String note = getString(R.string.ac_l10n_diary_note, getString(R.string.ac_diary_at_home),
                AcText.join(getResources(), lines));
        if (note.length() > 2000) note = note.substring(0, 2000);
        CreateMealEntryRequest request = new CreateMealEntryRequest()
                .date(selected.toString())
                .mealType(meal)
                .name(dish)
                .amount("1")
                .basis(kcal == null ? KcalBasis.UNKNOWN : KcalBasis.MANUAL)
                .basisKcal(kcal == null ? null : plain(kcal))
                .note(note);
        host.calls().enqueue(host.api().records().createMealEntry(UUID.randomUUID(), request),
                new ApiCallback<MealEntry>() {
                    @Override public void onSuccess(@Nullable MealEntry value) {
                        if (!isAdded()) return;
                        toast(R.string.ac_diary_saved);
                        loadMonth();
                    }

                    @Override public void onError(ApiError error) {
                        if (!isAdded()) return;
                        toast(R.string.ac_diary_load_error);
                    }
                });
    }

    private void editEntry(@Nullable MealEntry entry, @Nullable CookingEvent prefill) {
        if (!isAdded()) return;
        boolean editing = entry != null;
        LinearLayout body = new LinearLayout(requireContext());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(8), dp(20), dp(8));

        EntryMealType[] meals = EntryMealType.values();
        Map<EntryMealType, MaterialButton> mealButtons = new LinkedHashMap<>();
        LinearLayout mealRow = new LinearLayout(requireContext());
        mealRow.setOrientation(LinearLayout.HORIZONTAL);
        EntryMealType initialMeal = editing ? entry.getMealType() : EntryMealType.LUNCH;
        for (EntryMealType meal : meals) {
            MaterialButton button = new MaterialButton(requireContext(), null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            button.setText(mealLabel(meal));
            button.setTextSize(12);
            button.setInsetTop(0);
            button.setInsetBottom(0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            params.setMarginEnd(dp(4));
            mealRow.addView(button, params);
            mealButtons.put(meal, button);
            button.setOnClickListener(ignored -> select(mealButtons, meal));
        }
        select(mealButtons, initialMeal);
        body.addView(mealRow);

        EditText name = field(getString(R.string.ac_diary_name_hint));
        if (editing) name.setText(entry.getName());
        else if (prefill != null && prefill.getRecipe() != null) name.setText(prefill.getRecipe().getRecipeName());
        body.addView(name);

        EditText amount = field(getString(R.string.ac_diary_amount_hint));
        amount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        amount.setText(editing ? entry.getAmount() : "1");
        body.addView(amount);

        KcalBasis[] bases = {KcalBasis.SERVING, KcalBasis.GRAM, KcalBasis.MANUAL, KcalBasis.UNKNOWN};
        Map<KcalBasis, MaterialButton> basisButtons = new LinkedHashMap<>();
        LinearLayout basisRow = new LinearLayout(requireContext());
        basisRow.setOrientation(LinearLayout.HORIZONTAL);
        KcalBasis initialBasis = editing ? entry.getBasis()
                : (prefill != null && prefill.getRecipe() != null
                        && prefill.getRecipe().getTotalKcal() != null ? KcalBasis.SERVING : KcalBasis.UNKNOWN);
        for (KcalBasis basis : bases) {
            MaterialButton button = new MaterialButton(requireContext(), null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            button.setText(basisLabel(basis));
            button.setTextSize(12);
            button.setInsetTop(0);
            button.setInsetBottom(0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            params.setMarginEnd(dp(4));
            basisRow.addView(button, params);
            basisButtons.put(basis, button);
            button.setOnClickListener(ignored -> select(basisButtons, basis));
        }
        select(basisButtons, initialBasis);
        body.addView(basisRow);

        EditText kcal = field(getString(R.string.ac_diary_kcal_hint));
        kcal.setSingleLine(true);
        kcal.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        kcal.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        if (editing && entry.getBasisKcal() != null) kcal.setText(entry.getBasisKcal());
        body.addView(kcal);

        EditText note = field(getString(R.string.ac_diary_note_hint));
        if (editing && entry.getNote() != null) note.setText(entry.getNote());
        body.addView(note);

        android.widget.ScrollView scroll = new android.widget.ScrollView(requireContext());
        scroll.addView(body);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(editing ? R.string.ac_diary_edit : R.string.ac_diary_new)
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.ac_diary_save, null);
        if (editing) builder.setNeutralButton(R.string.ac_diary_delete, null);
        final androidx.appcompat.app.AlertDialog dialog = builder.create();
        Runnable save = () -> submit(mealButtons, meals, basisButtons, bases, name, amount, kcal, note,
                entry, prefill, dialog::dismiss);
        kcal.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                save.run();
                return true;
            }
            return false;
        });
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> save.run());
            if (editing) {
                dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener(view ->
                        new MaterialAlertDialogBuilder(requireContext())
                                .setMessage(R.string.ac_diary_delete_confirm)
                                .setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton(R.string.ac_diary_delete, (dialog2, which) ->
                                        host.calls().enqueue(host.api().records().deleteMealEntry(
                                                entry.getId(), entry.getVersion()), new ApiCallback<Void>() {
                                            @Override public void onSuccess(@Nullable Void value) {
                                                if (!isAdded()) return;
                                                dialog.dismiss();
                                                loadMonth();
                                                toast(R.string.ac_diary_deleted);
                                            }

                                            @Override public void onError(ApiError error) {
                                                if (!isAdded()) return;
                                                toast(R.string.ac_diary_load_error);
                                            }
                                        }))
                                .show());
            }
        });
        dialog.show();
    }

    private void submit(Map<EntryMealType, MaterialButton> mealButtons, EntryMealType[] meals,
                        Map<KcalBasis, MaterialButton> basisButtons, KcalBasis[] bases,
                        EditText name, EditText amount, EditText kcal, EditText note,
                        @Nullable MealEntry entry, @Nullable CookingEvent prefill, @Nullable Runnable afterSave) {
        if (!isAdded()) return;
        EntryMealType meal = selected(mealButtons, meals);
        KcalBasis basis = selected(basisButtons, bases);
        String title = name.getText().toString().trim();
        BigDecimal amountValue = decimal(amount.getText().toString());
        if (title.isEmpty() || amountValue == null || amountValue.signum() <= 0) {
            toast(R.string.ac_diary_invalid);
            return;
        }
        if (basis == KcalBasis.MANUAL && amountValue.compareTo(BigDecimal.ONE) != 0) {
            toast(R.string.ac_diary_manual_one);
            return;
        }
        BigDecimal basisKcal = decimal(kcal.getText().toString());
        if (basis != KcalBasis.UNKNOWN && (basisKcal == null || basisKcal.signum() < 0)) {
            toast(R.string.ac_diary_invalid);
            return;
        }
        String kcalText = basis == KcalBasis.UNKNOWN ? null : plain(basisKcal);
        ApiCallback<MealEntry> saved = savedCallback();
        if (entry != null) {
            UpdateMealEntryRequest request = new UpdateMealEntryRequest()
                    .date(selected.toString()).mealType(meal).name(title)
                    .amount(plain(amountValue)).basis(basis).basisKcal(kcalText)
                    .note(note.getText().toString()).expectedVersion(entry.getVersion());
            host.calls().enqueue(host.api().records().updateMealEntry(entry.getId(), request), saved);
        } else {
            CreateMealEntryRequest request = new CreateMealEntryRequest()
                    .date(selected.toString()).mealType(meal).name(title)
                    .amount(plain(amountValue)).basis(basis).basisKcal(kcalText)
                    .note(note.getText().toString())
                    .cookingEventId(prefill == null ? null : prefill.getId());
            host.calls().enqueue(host.api().records().createMealEntry(UUID.randomUUID(), request), saved);
        }
        if (afterSave != null) afterSave.run();
    }

    private ApiCallback<MealEntry> savedCallback() {
        return new ApiCallback<MealEntry>() {
            @Override public void onSuccess(@Nullable MealEntry value) {
                if (!isAdded()) return;
                toast(R.string.ac_diary_saved);
                loadMonth();
            }

            @Override public void onError(ApiError error) {
                if (!isAdded()) return;
                toast(R.string.ac_diary_load_error);
            }
        };
    }

    private <T> T selected(Map<T, MaterialButton> buttons, T[] order) {
        for (T key : order) {
            MaterialButton button = buttons.get(key);
            if (button != null && button.getTag() != null && Boolean.TRUE.equals(button.getTag())) return key;
        }
        return order[0];
    }

    private <T> void selectMeal(Map<T, MaterialButton> buttons, T selected) {
        select(buttons, selected);
    }

    private <T> void selectBasis(Map<T, MaterialButton> buttons, T selected) {
        select(buttons, selected);
    }

    private <T> void select(Map<T, MaterialButton> buttons, T selected) {
        for (Map.Entry<T, MaterialButton> entry : buttons.entrySet()) {
            boolean active = entry.getKey() == selected;
            entry.getValue().setTag(active);
            entry.getValue().setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    requireContext().getColor(active ? R.color.ac_catalog_accent : android.R.color.transparent)));
            entry.getValue().setTextColor(requireContext().getColor(
                    active ? android.R.color.white : R.color.ac_catalog_ink));
        }
    }

    private EditText field(String hint) {
        EditText field = new EditText(requireContext());
        field.setHint(hint);
        field.setTextSize(15);
        field.setBackgroundResource(R.drawable.ac_catalog_search);
        field.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        field.setLayoutParams(params);
        return field;
    }

    private TextView section(String text) {
        TextView view = line(text, 13, R.color.ac_catalog_muted);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setPadding(0, dp(10), 0, dp(4));
        return view;
    }

    private TextView line(String text, int size, int color) {
        TextView view = new TextView(requireContext());
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(requireContext().getColor(color));
        view.setLineSpacing(0f, 1.2f);
        return view;
    }

    private String mealLabel(EntryMealType meal) {
        return switch (meal) {
            case BREAKFAST -> getString(R.string.ac_diary_breakfast);
            case LUNCH -> getString(R.string.ac_diary_lunch);
            case DINNER -> getString(R.string.ac_diary_dinner);
            case SNACK -> getString(R.string.ac_diary_snack);
        };
    }

    private String basisLabel(KcalBasis basis) {
        return switch (basis) {
            case SERVING -> getString(R.string.ac_diary_basis_serving);
            case GRAM -> getString(R.string.ac_diary_basis_gram);
            case MANUAL -> getString(R.string.ac_diary_basis_manual);
            case UNKNOWN -> getString(R.string.ac_diary_basis_unknown);
        };
    }

    private static BigDecimal decimal(String value) {
        try {
            return value == null || value.trim().isEmpty() ? null : new BigDecimal(value.trim());
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static String plain(BigDecimal value) {
        return value.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private void toast(int text) {
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
