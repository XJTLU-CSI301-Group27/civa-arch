package edu.xjtlu.civa.feature.ac.plan;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import edu.xjtlu.civa.api.model.PlanItem;
import edu.xjtlu.civa.api.model.PlanMealType;
import edu.xjtlu.civa.api.model.PlanSlot;
import edu.xjtlu.civa.api.model.PlanSlotPage;
import edu.xjtlu.civa.api.model.ReplacePlanSlotRequest;
import edu.xjtlu.civa.api.model.Recipe;
import edu.xjtlu.civa.api.model.RecipePage;
import edu.xjtlu.civa.api.model.SetPlanLockRequest;
import edu.xjtlu.civa.core.data.ApiCallback;
import edu.xjtlu.civa.core.ui.ApiError;
import edu.xjtlu.civa.core.ui.Haptics;
import edu.xjtlu.civa.core.ui.Motion;
import edu.xjtlu.civa.feature.ac.AcHost;
import edu.xjtlu.civa.feature.ac.AcText;
import edu.xjtlu.civa.feature.ac.R;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Household meal plan: 14 days, three meals, per-slot choose/clear/lock. */
public final class PlanFragment extends Fragment {
    private static final PlanMealType[] MEALS =
            {PlanMealType.BREAKFAST, PlanMealType.LUNCH, PlanMealType.DINNER};
    private enum Mode { PERSONAL, HOUSEHOLD }
    private static Mode lastMode = Mode.HOUSEHOLD;
    private Mode mode = lastMode;

    private AcHost host;
    private LinearLayout listView;
    private TextView subtitle;
    private final Map<String, PlanSlot> slots = new LinkedHashMap<>();
    private final Map<UUID, String> recipeNames = new LinkedHashMap<>();
    private final List<Recipe> recipes = new ArrayList<>();
    private LocalDate start = LocalDate.now();
    private UUID home;
    private boolean loading;

    public PlanFragment() { super(R.layout.ac_fragment_plan); }

    /** Date formats follow the app language instead of a fixed Chinese locale. */
    private DateTimeFormatter dateFormat(int pattern) {
        return DateTimeFormatter.ofPattern(getString(pattern), AcText.locale(getResources()));
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        host = (AcHost) requireActivity();
        listView = view.findViewById(R.id.ac_plan_list);
        subtitle = view.findViewById(R.id.ac_plan_subtitle);
        view.findViewById(R.id.ac_plan_refresh).setOnClickListener(ignored -> load());
        view.findViewById(R.id.ac_plan_mode_personal).setOnClickListener(ignored -> switchMode(Mode.PERSONAL));
        view.findViewById(R.id.ac_plan_mode_household).setOnClickListener(ignored -> switchMode(Mode.HOUSEHOLD));
        paintModes();
        load();
    }

    private void switchMode(Mode next) {
        if (mode == next) return;
        mode = next;
        lastMode = next;
        paintModes();
        load();
    }

    private void paintModes() {
        if (!isAdded()) return;
        paintModeButton(requireView().findViewById(R.id.ac_plan_mode_personal), mode == Mode.PERSONAL);
        paintModeButton(requireView().findViewById(R.id.ac_plan_mode_household), mode == Mode.HOUSEHOLD);
    }

    private void paintModeButton(MaterialButton button, boolean selected) {
        int target = requireContext().getColor(selected ? R.color.ac_catalog_accent : android.R.color.transparent);
        android.content.res.ColorStateList current = button.getBackgroundTintList();
        int from = current == null ? android.graphics.Color.TRANSPARENT : current.getDefaultColor();
        button.setTextColor(requireContext().getColor(selected ? android.R.color.white : R.color.ac_catalog_ink));
        if (from == target || !Motion.enabled()) {
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(target));
            return;
        }
        android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofArgb(from, target);
        animator.setDuration(160L);
        animator.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
        animator.addUpdateListener(animation -> button.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf((int) animation.getAnimatedValue())));
        animator.start();
    }

    private void load() {
        if (!isAdded()) return;
        if (mode == Mode.PERSONAL) {
            renderPersonal();
            return;
        }
        String homeText = host.sessions() == null ? null : host.sessions().currentHouseholdId();
        if (homeText == null) {
            home = null;
            render();
            return;
        }
        home = UUID.fromString(homeText);
        loading = true;
        subtitle.setText(R.string.ac_plan_loading);
        host.calls().enqueue(host.api().recipe().listRecipes(home, null, null, null, false, 0, 100),
                new ApiCallback<RecipePage>() {
                    @Override public void onSuccess(@Nullable RecipePage value) {
                        if (!isAdded()) return;
                        recipes.clear();
                        recipeNames.clear();
                        if (value != null && value.getItems() != null) {
                            for (Recipe recipe : value.getItems()) {
                                if (Boolean.TRUE.equals(recipe.getArchived())) continue;
                                recipes.add(recipe);
                                recipeNames.put(recipe.getId(), recipe.getName());
                            }
                        }
                        loadSlots();
                    }

                    @Override public void onError(ApiError error) {
                        if (!isAdded()) return;
                        loading = false;
                        subtitle.setText(R.string.ac_plan_error);
                    }
                });
    }

    private void loadSlots() {
        String from = start.toString();
        String to = start.plusDays(13).toString();
        host.calls().enqueue(host.api().planning().listPlanSlots(home, from, to, 0, 100),
                new ApiCallback<PlanSlotPage>() {
                    @Override public void onSuccess(@Nullable PlanSlotPage value) {
                        if (!isAdded()) return;
                        loading = false;
                        slots.clear();
                        if (value != null && value.getItems() != null) {
                            for (PlanSlot slot : value.getItems()) {
                                slots.put(slot.getDate() + "/" + slot.getMealType().getValue(), slot);
                            }
                        }
                        subtitle.setText(getString(R.string.ac_plan_subtitle,
                                start.format(dateFormat(R.string.ac_l10n_date_day_short)),
                                start.plusDays(13).format(dateFormat(R.string.ac_l10n_date_day_short))));
                        render();
                    }

                    @Override public void onError(ApiError error) {
                        if (!isAdded()) return;
                        loading = false;
                        subtitle.setText(R.string.ac_plan_error);
                    }
                });
    }

    private void renderPersonal() {
        if (listView == null) return;
        loading = false;
        listView.removeAllViews();
        subtitle.setText(R.string.ac_plan_personal_hint);
        for (int day = 0; day < 14; day++) {
            LocalDate date = start.plusDays(day);
            listView.addView(dayHeader(date));
            List<edu.xjtlu.civa.core.data.PlannedDish> dishes;
            try {
                dishes = host.planFor(date);
            } catch (RuntimeException unavailable) {
                dishes = List.of();
            }
            for (PlanMealType meal : MEALS) {
                List<String> parts = new ArrayList<>();
                for (edu.xjtlu.civa.core.data.PlannedDish dish : dishes) {
                    if (meal.getValue().equals(dish.meal())) parts.add(dish.name());
                }
                LinearLayout row = new LinearLayout(requireContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(dp(10), dp(8), dp(10), dp(8));
                row.setBackgroundResource(R.drawable.ac_catalog_card);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.bottomMargin = dp(4);
                row.setLayoutParams(params);
                row.addView(text(mealLabel(meal) + " · "
                        + (parts.isEmpty() ? getString(R.string.ac_plan_empty)
                                : AcText.join(getResources(), parts)),
                        14, R.color.ac_catalog_ink), new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                listView.addView(row);
            }
        }
    }

    private void render() {
        if (listView == null) return;
        listView.removeAllViews();
        if (home == null) {
            listView.addView(text(getString(R.string.ac_plan_join_home), 14, R.color.ac_catalog_muted));
            subtitle.setText("");
            return;
        }
        for (int day = 0; day < 14; day++) {
            LocalDate date = start.plusDays(day);
            listView.addView(dayHeader(date));
            for (PlanMealType meal : MEALS) renderMeal(date, meal);
        }
    }

    private View dayHeader(LocalDate date) {
        TextView view = text(date.format(dateFormat(R.string.ac_l10n_date_day_week)), 15, R.color.ac_catalog_ink);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setPadding(0, dp(14), 0, dp(6));
        return view;
    }

    private void renderMeal(LocalDate date, PlanMealType meal) {
        String dateText = date.toString();
        PlanSlot slot = slots.get(dateText + "/" + meal.getValue());
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setBackgroundResource(R.drawable.ac_catalog_card);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(4);
        row.setLayoutParams(params);

        String label = mealLabel(meal) + " · " + describe(slot);
        if (slot != null && slot.getLastEditorName() != null && !slot.getLastEditorName().isBlank()) {
            label += getString(R.string.ac_plan_edited_by, slot.getLastEditorName());
        }
        if (slot != null && Boolean.TRUE.equals(slot.getLocked())) label = "🔒 " + label;
        TextView text = text(label, 14, R.color.ac_catalog_ink);
        text.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(text);

        MaterialButton choose = smallButton(R.string.ac_plan_choose);
        choose.setEnabled(!loading);
        choose.setOnClickListener(ignored -> chooseRecipe(date, meal, slot));
        row.addView(choose);

        MaterialButton lock = smallButton(Boolean.TRUE.equals(slot != null && slot.getLocked())
                ? R.string.ac_plan_unlock : R.string.ac_plan_lock);
        lock.setOnClickListener(ignored -> toggleLock(date, meal, slot));
        row.addView(lock);
        listView.addView(row);
    }

    private String describe(@Nullable PlanSlot slot) {
        if (slot == null || slot.getItems() == null || slot.getItems().isEmpty()) {
            return getString(R.string.ac_plan_empty);
        }
        List<String> parts = new ArrayList<>();
        for (PlanItem item : slot.getItems()) {
            String name = recipeNames.getOrDefault(item.getRecipeId(), getString(R.string.ac_plan_unknown_dish));
            parts.add(name + " ×" + item.getServings());
        }
        return AcText.join(getResources(), parts);
    }

    private void chooseRecipe(LocalDate date, PlanMealType meal, @Nullable PlanSlot slot) {
        if (slot != null && Boolean.TRUE.equals(slot.getLocked())) {
            toast(R.string.ac_plan_locked);
            return;
        }
        if (recipes.isEmpty()) {
            toast(R.string.ac_plan_no_recipes);
            return;
        }
        String[] names = new String[recipes.size()];
        for (int i = 0; i < recipes.size(); i++) {
            Recipe recipe = recipes.get(i);
            names[i] = recipe.getYieldServings() == null ? recipe.getName()
                    : getString(R.string.ac_l10n_plan_recipe_option, recipe.getName(),
                            recipe.getYieldServings());
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.ac_plan_choose_title, mealLabel(meal)))
                .setItems(names, (dialog, which) -> replaceSlot(date, meal,
                        recipes.get(which).getId(), "1",
                        slot == null ? 0 : slot.getVersion()))
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.ac_plan_clear, (dialog, which) -> replaceSlot(date, meal,
                        null, null, slot == null ? 0 : slot.getVersion()))
                .show();
    }

    private void replaceSlot(LocalDate date, PlanMealType meal, @Nullable UUID recipeId, @Nullable String servings,
                             long expectedVersion) {
        List<PlanItem> items = new ArrayList<>();
        if (recipeId != null) items.add(new PlanItem().recipeId(recipeId).servings(servings == null ? "1" : servings));
        ReplacePlanSlotRequest request = new ReplacePlanSlotRequest().items(items).expectedVersion(expectedVersion);
        host.calls().enqueue(host.api().planning().replacePlanSlot(home, date.toString(), meal, request),
                new ApiCallback<PlanSlot>() {
                    @Override public void onSuccess(@Nullable PlanSlot value) {
                        if (!isAdded()) return;
                        toast(R.string.ac_plan_saved);
                        loadSlots();
                    }

                    @Override public void onError(ApiError error) {
                        if (!isAdded()) return;
                        toast(R.string.ac_plan_conflict);
                    }
                });
    }

    private void toggleLock(LocalDate date, PlanMealType meal, @Nullable PlanSlot slot) {
        boolean locked = slot == null || !Boolean.TRUE.equals(slot.getLocked());
        long version = slot == null ? 0 : slot.getVersion();
        host.calls().enqueue(host.api().planning().setPlanSlotLock(home, date.toString(), meal,
                        new SetPlanLockRequest().locked(locked).expectedVersion(version)),
                new ApiCallback<PlanSlot>() {
                    @Override public void onSuccess(@Nullable PlanSlot value) {
                        if (!isAdded()) return;
                        Haptics.tap(requireView());
                        loadSlots();
                    }

                    @Override public void onError(ApiError error) {
                        if (!isAdded()) return;
                        toast(R.string.ac_plan_conflict);
                    }
                });
    }

    private MaterialButton smallButton(int text) {
        MaterialButton button = new MaterialButton(requireContext(), null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(text);
        button.setTextSize(11);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(dp(4));
        button.setLayoutParams(params);
        return button;
    }

    private String mealLabel(PlanMealType meal) {
        return switch (meal) {
            case BREAKFAST -> getString(R.string.ac_diary_breakfast);
            case LUNCH -> getString(R.string.ac_diary_lunch);
            case DINNER -> getString(R.string.ac_diary_dinner);
        };
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(requireContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(requireContext().getColor(color));
        view.setLineSpacing(0f, 1.2f);
        return view;
    }

    private void toast(int text) {
        edu.xjtlu.civa.core.ui.Feedback.show(requireActivity(), text, false);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
