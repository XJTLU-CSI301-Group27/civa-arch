package edu.xjtlu.civa.feature.ac.plan;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import edu.xjtlu.civa.api.model.Household;
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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Household meal plan. Members may only append dishes and increase servings; the owner also manages
 * servings, replacement, removal, clearing and locking. The server remains the final authority.
 */
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
    private UUID ownerUserId;
    private boolean loading;
    private boolean sending;
    private boolean resumedOnce;
    private int loadGeneration;

    public PlanFragment() { super(R.layout.ac_fragment_plan); }

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

    /** Re-reads ownership when the user returns to the plan, e.g. after a transfer elsewhere. */
    @Override public void onResume() {
        super.onResume();
        if (resumedOnce && isAdded() && listView != null && !loading && !sending) load();
        resumedOnce = true;
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
            loadGeneration++;
            renderPersonal();
            return;
        }
        int generation = ++loadGeneration;
        String homeText = host.sessions() == null ? null : host.sessions().currentHouseholdId();
        if (homeText == null) {
            home = null;
            ownerUserId = null;
            render();
            return;
        }
        home = UUID.fromString(homeText);
        loading = true;
        sending = false;
        ownerUserId = null;
        subtitle.setText(R.string.ac_plan_loading);
        host.calls().enqueue(host.api().household().getHousehold(home), new ApiCallback<Household>() {
            @Override public void onSuccess(@Nullable Household value) {
                if (generation != loadGeneration || !isAdded()) return;
                ownerUserId = value == null ? null : value.getOwnerUserId();
                loadRecipes(generation);
            }

            @Override public void onError(ApiError error) {
                if (generation != loadGeneration || !isAdded()) return;
                loading = false;
                ownerUserId = null;
                subtitle.setText(R.string.ac_plan_error);
                render();
            }
        });
    }

    private void loadRecipes(int generation) {
        host.calls().enqueue(host.api().recipe().listRecipes(home, null, null, null, false, 0, 100),
                new ApiCallback<RecipePage>() {
                    @Override public void onSuccess(@Nullable RecipePage value) {
                        if (generation != loadGeneration || !isAdded()) return;
                        recipes.clear();
                        recipeNames.clear();
                        if (value != null && value.getItems() != null) {
                            for (Recipe recipe : value.getItems()) {
                                if (Boolean.TRUE.equals(recipe.getArchived())) continue;
                                recipes.add(recipe);
                                recipeNames.put(recipe.getId(), recipe.getName());
                            }
                        }
                        loadSlots(generation);
                    }

                    @Override public void onError(ApiError error) {
                        if (generation != loadGeneration || !isAdded()) return;
                        loading = false;
                        subtitle.setText(R.string.ac_plan_error);
                    }
                });
    }

    private void loadSlots(int generation) {
        String from = start.toString();
        String to = start.plusDays(13).toString();
        host.calls().enqueue(host.api().planning().listPlanSlots(home, from, to, 0, 100),
                new ApiCallback<PlanSlotPage>() {
                    @Override public void onSuccess(@Nullable PlanSlotPage value) {
                        if (generation != loadGeneration || !isAdded()) return;
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
                        if (generation != loadGeneration || !isAdded()) return;
                        loading = false;
                        subtitle.setText(R.string.ac_plan_error);
                    }
                });
    }

    private void refreshSlots() {
        if (home != null) loadSlots(++loadGeneration);
    }

    private boolean isOwner() {
        String user = host == null || host.sessions() == null ? null : host.sessions().currentUserId();
        return ownerUserId != null && user != null && ownerUserId.toString().equals(user);
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
        boolean locked = slot != null && Boolean.TRUE.equals(slot.getLocked());
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
        if (locked) label = "🔒 " + label;
        TextView text = text(label, 14, R.color.ac_catalog_ink);
        text.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(text);

        MaterialButton add = smallButton(R.string.ac_plan_add);
        add.setEnabled(!loading && !sending && !locked);
        add.setOnClickListener(ignored -> chooseRecipe(date, meal, slot));
        row.addView(add);

        if (isOwner()) {
            MaterialButton manage = smallButton(R.string.ac_plan_manage);
            manage.setEnabled(!loading && !sending && !locked
                    && slot != null && slot.getItems() != null && !slot.getItems().isEmpty());
            manage.setOnClickListener(ignored -> manageSlot(date, meal, slot));
            row.addView(manage);

            MaterialButton lock = smallButton(locked ? R.string.ac_plan_unlock : R.string.ac_plan_lock);
            lock.setEnabled(!loading && !sending);
            lock.setOnClickListener(ignored -> toggleLock(date, meal, slot));
            row.addView(lock);
        }
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
        pickRecipe(R.string.ac_plan_choose_title, mealLabel(meal), recipes,
                recipe -> addDish(date, meal, slot, recipe));
    }

    /** Member-safe append: keeps every existing dish and only adds or increases one. */
    private void addDish(LocalDate date, PlanMealType meal, @Nullable PlanSlot slot, Recipe recipe) {
        List<PlanItem> items = PlanItemEditor.append(slot == null ? null : slot.getItems(), recipe.getId());
        sendSlot(date, meal, items, slot == null ? 0L : slot.getVersion());
    }

    private void pickRecipe(int title, String titleArg, List<Recipe> options, Consumer<Recipe> onPick) {
        if (options.isEmpty()) {
            toast(R.string.ac_plan_no_recipes);
            return;
        }
        String[] names = new String[options.size()];
        for (int index = 0; index < options.size(); index++) {
            Recipe recipe = options.get(index);
            names[index] = recipe.getYieldServings() == null ? recipe.getName()
                    : getString(R.string.ac_l10n_plan_recipe_option, recipe.getName(),
                            recipe.getYieldServings());
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(title, titleArg))
                .setItems(names, (dialog, which) -> onPick.accept(options.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void manageSlot(LocalDate date, PlanMealType meal, @Nullable PlanSlot slot) {
        if (!isOwner()) {
            toast(R.string.ac_plan_owner_only);
            return;
        }
        if (slot == null || slot.getItems() == null || slot.getItems().isEmpty()) {
            toast(R.string.ac_plan_empty);
            return;
        }
        if (Boolean.TRUE.equals(slot.getLocked())) {
            toast(R.string.ac_plan_locked);
            return;
        }
        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(4), dp(16), dp(4));
        final AlertDialog[] holder = new AlertDialog[1];
        for (PlanItem item : PlanItemEditor.copy(slot.getItems())) {
            content.addView(manageRow(date, meal, slot, item, holder));
        }
        ScrollView scroll = new ScrollView(requireContext());
        scroll.addView(content);
        holder[0] = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.ac_plan_manage_title, mealLabel(meal)))
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.ac_plan_clear, (dialog, which) -> confirmClear(date, meal, slot))
                .create();
        holder[0].show();
    }

    private View manageRow(LocalDate date, PlanMealType meal, PlanSlot slot, PlanItem item,
                           AlertDialog[] holder) {
        LinearLayout block = new LinearLayout(requireContext());
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, dp(6), 0, dp(6));

        String name = recipeNames.getOrDefault(item.getRecipeId(), getString(R.string.ac_plan_unknown_dish));
        block.addView(text(name + " ×" + item.getServings(), 14, R.color.ac_catalog_ink));

        LinearLayout actions = new LinearLayout(requireContext());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        boolean canDecrease = new BigDecimal(item.getServings()).compareTo(BigDecimal.ONE) > 0;

        MaterialButton minus = smallButtonText("−");
        minus.setContentDescription(getString(R.string.ac_plan_decrease));
        minus.setEnabled(canDecrease);
        minus.setOnClickListener(ignored -> applyOwnerEdit(date, meal, slot,
                items -> PlanItemEditor.adjust(items, item.getRecipeId(), -1), holder[0]));
        actions.addView(minus);

        MaterialButton plus = smallButtonText("+");
        plus.setContentDescription(getString(R.string.ac_plan_increase));
        plus.setOnClickListener(ignored -> applyOwnerEdit(date, meal, slot,
                items -> PlanItemEditor.adjust(items, item.getRecipeId(), 1), holder[0]));
        actions.addView(plus);

        MaterialButton replace = smallButton(R.string.ac_plan_replace);
        replace.setOnClickListener(ignored -> replaceDish(date, meal, slot, item, holder[0]));
        actions.addView(replace);

        MaterialButton delete = smallButton(R.string.ac_plan_delete);
        delete.setOnClickListener(ignored -> applyOwnerEdit(date, meal, slot,
                items -> PlanItemEditor.remove(items, item.getRecipeId()), holder[0]));
        actions.addView(delete);

        block.addView(actions);
        return block;
    }

    private void replaceDish(LocalDate date, PlanMealType meal, PlanSlot slot, PlanItem item,
                             @Nullable AlertDialog dialog) {
        Set<UUID> used = new HashSet<>();
        for (PlanItem current : slot.getItems()) used.add(current.getRecipeId());
        List<Recipe> options = new ArrayList<>();
        for (Recipe recipe : recipes) {
            if (!used.contains(recipe.getId())) options.add(recipe);
        }
        String currentName = recipeNames.getOrDefault(item.getRecipeId(), getString(R.string.ac_plan_unknown_dish));
        pickRecipe(R.string.ac_plan_replace_title, currentName, options, replacement -> {
            if (dialog != null) dialog.dismiss();
            sendSlot(date, meal,
                    PlanItemEditor.replace(slot.getItems(), item.getRecipeId(), replacement.getId()),
                    slot.getVersion());
        });
    }

    private void applyOwnerEdit(LocalDate date, PlanMealType meal, PlanSlot slot,
                                Function<List<PlanItem>, List<PlanItem>> edit, @Nullable AlertDialog dialog) {
        if (sending) return;
        List<PlanItem> items = edit.apply(PlanItemEditor.copy(slot.getItems()));
        if (dialog != null) dialog.dismiss();
        sendSlot(date, meal, items, slot.getVersion());
    }

    private void confirmClear(LocalDate date, PlanMealType meal, PlanSlot slot) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ac_plan_clear_confirm_title)
                .setMessage(R.string.ac_plan_clear_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.ac_plan_clear_confirm, (dialog, which) ->
                        sendSlot(date, meal, List.of(), slot.getVersion()))
                .show();
    }

    private void sendSlot(LocalDate date, PlanMealType meal, List<PlanItem> items, long expectedVersion) {
        if (sending || !isAdded()) return;
        sending = true;
        ReplacePlanSlotRequest request = new ReplacePlanSlotRequest()
                .items(items).expectedVersion(expectedVersion);
        host.calls().enqueue(host.api().planning().replacePlanSlot(home, date.toString(), meal, request),
                new ApiCallback<PlanSlot>() {
                    @Override public void onSuccess(@Nullable PlanSlot value) {
                        if (!isAdded()) return;
                        sending = false;
                        toast(R.string.ac_plan_saved);
                        refreshSlots();
                    }

                    @Override public void onError(ApiError error) {
                        if (!isAdded()) return;
                        sending = false;
                        handlePlanError(error);
                    }
                });
    }

    private void toggleLock(LocalDate date, PlanMealType meal, @Nullable PlanSlot slot) {
        if (!isOwner()) {
            toast(R.string.ac_plan_owner_only);
            return;
        }
        if (sending) return;
        boolean locked = slot == null || !Boolean.TRUE.equals(slot.getLocked());
        long version = slot == null ? 0 : slot.getVersion();
        sending = true;
        host.calls().enqueue(host.api().planning().setPlanSlotLock(home, date.toString(), meal,
                        new SetPlanLockRequest().locked(locked).expectedVersion(version)),
                new ApiCallback<PlanSlot>() {
                    @Override public void onSuccess(@Nullable PlanSlot value) {
                        if (!isAdded()) return;
                        sending = false;
                        Haptics.tap(requireView());
                        refreshSlots();
                    }

                    @Override public void onError(ApiError error) {
                        if (!isAdded()) return;
                        sending = false;
                        handlePlanError(error);
                    }
                });
    }

    private void handlePlanError(@Nullable ApiError error) {
        String code = error == null || error.code == null ? "" : error.code;
        switch (code) {
            case "PLAN_PERMISSION_DENIED", "NOT_HOUSEHOLD_OWNER" -> {
                toast(R.string.ac_plan_owner_only);
                load();
            }
            case "VERSION_CONFLICT" -> {
                toast(R.string.ac_plan_conflict);
                load();
            }
            case "PLAN_SLOT_LOCKED", "PLAN_LOCKED" -> {
                toast(R.string.ac_plan_locked);
                refreshSlots();
            }
            case "NOT_FOUND" -> {
                toast(R.string.ac_plan_error);
                load();
            }
            default -> toast(R.string.ac_plan_error);
        }
    }

    private MaterialButton smallButton(int text) {
        MaterialButton button = compactButton();
        button.setText(text);
        button.setTextSize(11);
        return button;
    }

    private MaterialButton smallButtonText(String value) {
        MaterialButton button = compactButton();
        button.setText(value);
        button.setTextSize(13);
        return button;
    }

    /** Three per-row actions must not squeeze the dish label into a vertical column on 360dp. */
    private MaterialButton compactButton() {
        MaterialButton button = new MaterialButton(requireContext(), null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setPadding(dp(6), 0, dp(6), 0);
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
