package edu.xjtlu.civa.feature.bd;

import edu.xjtlu.civa.core.ui.PressBounce;
import edu.xjtlu.civa.core.ui.DishActionOverlay;
import edu.xjtlu.civa.core.ui.DishPlateView;
import edu.xjtlu.civa.core.ui.ElasticChoiceView;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/** One restorable dialog window with explicit prototype back/close transitions. */
public final class BdFlowDialog extends DialogFragment {
    private BdHomeViewModel model;
    private LinearLayout body;
    private TextView title, error, count, photoCaption;
    private View close;
    private DishPlateView photo;
    private MaterialButton primary, secondary, removeButton;
    private String rendered;
    private DishActionOverlay presentation;
    private Boolean anchoredWindow;
    private View panel;

    @Override public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setCancelable(false);
        model = new ViewModelProvider(requireActivity()).get(BdHomeViewModel.class);
    }

    @NonNull @Override public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        AppCompatDialog dialog = new AppCompatDialog(requireContext());
        dialog.supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        View content = getLayoutInflater().inflate(R.layout.bd_dialog_add_dish, null);
        panel = content;
        body = content.findViewById(R.id.bd_dish_body);
        title = content.findViewById(R.id.bd_dish_title);
        close = content.findViewById(R.id.bd_dish_close);
        PressBounce.bind(close);
        close.setOnClickListener(ignored -> model.back());
        presentation = new DishActionOverlay(requireContext(), content,
                () -> getParentFragment() instanceof BdHomeFragment ? ((BdHomeFragment) getParentFragment()).selectedDishAnchorBounds() : null,
                () -> getParentFragment() instanceof BdHomeFragment ? ((BdHomeFragment) getParentFragment()).getView() : null);
        presentation.setOnClickListener(ignored -> { if (model.phase == BdHomeViewModel.Phase.DISH) model.back(); });
        dialog.setContentView(presentation);
        dialog.setCanceledOnTouchOutside(false);
        dialog.getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { model.back(); }
        });
        model.changes.observe(this, ignored -> render());
        return dialog;
    }

    @Override public void onStart() {
        super.onStart();
        Window window = requireDialog().getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            // The first phase has no editor; Dialog would otherwise keep its initial IME-blocking flag.
            window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            anchoredWindow = null; // Reapply bounds when the dialog window is attached/restored.
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = .32f; window.setAttributes(attributes);
        }
        render();
    }

    private void render() {
        if (body == null) return;
        if (model.phase == BdHomeViewModel.Phase.NONE) { dismissAllowingStateLoss(); return; }
        updatePresentation();
        ((DialogScroll) body.getParent()).setPreferences(model.phase == BdHomeViewModel.Phase.PREFERENCES);
        boolean centered = model.phase == BdHomeViewModel.Phase.DISH;
        title.setGravity(centered ? Gravity.CENTER : Gravity.START | Gravity.CENTER_VERTICAL);
        title.setTextSize(centered ? 17 : 23); title.setTranslationY(centered ? dp(2) : 0);
        int padding = dp(centered ? 10 : 16); panel.setPadding(padding, padding, padding, padding);
        View header = (View) title.getParent();
        android.view.ViewGroup.LayoutParams headerParams = header.getLayoutParams();
        if (headerParams.height != dp(centered ? 48 : 64)) { headerParams.height = dp(centered ? 48 : 64); header.setLayoutParams(headerParams); }
        ((View) body.getParent()).setPadding(dp(centered ? 0 : 8), 0, dp(centered ? 0 : 8), dp(centered ? 0 : 8));
        android.widget.FrameLayout.LayoutParams titleParams = (android.widget.FrameLayout.LayoutParams) title.getLayoutParams();
        int startMargin = dp(centered ? 28 : 48), endMargin = dp(centered ? 28 : 12);
        if (titleParams.getMarginStart() != startMargin || titleParams.getMarginEnd() != endMargin) {
            titleParams.setMarginStart(startMargin); titleParams.setMarginEnd(endMargin); title.setLayoutParams(titleParams);
        }
        String key = model.phase + "/" + model.ready + "/" + (model.candidate == null ? "" : model.candidate.id)
                + (model.detailFlow() ? "/" + model.planVersion + "/" + model.nutritionLoading + "/" + (model.nutrition != null) : "");
        if (!key.equals(rendered)) {
            body.removeAllViews(); primary = null; secondary = null; removeButton = null; photo = null; photoCaption = null; count = null;
            error = text("", 13, true); error.setTextColor(0xFF995746); error.setVisibility(View.GONE);
            body.addView(error, full(0));
            switch (model.phase) {
                case CHOOSE: buildChoice(); break;
                case MANUAL: buildManual(); break;
                case PREFERENCES: buildPreferences(); break;
                case RECOMMENDATION: buildRecommendation(); break;
                case DISH: buildDishMenu(); break;
                case RECIPE: buildRecipe(); break;
                case MISSING_RECIPE: buildMissingRecipe(); break;
                case EDIT_RECIPE: buildRecipeEditor(); break;
                case NUTRITION: buildNutrition(); break;
                default: break;
            }
            rendered = key;
        }
        error.setText(model.message);
        error.setVisibility(model.message == null ? View.GONE : View.VISIBLE);
        close.setEnabled(!model.busy());
        close.setContentDescription(getString(model.detailFlow() || model.phase == BdHomeViewModel.Phase.CHOOSE || model.phase == BdHomeViewModel.Phase.RECOMMENDATION
                ? R.string.bd_dish_close_home : R.string.bd_dish_close));
        if (primary != null) primary.setEnabled(model.ready && !model.busy());
        if (secondary != null) secondary.setEnabled(model.ready && !model.busy()
                && (model.phase != BdHomeViewModel.Phase.RECOMMENDATION || model.canReroll()));
        if (removeButton != null) removeButton.setEnabled(model.ready && !model.busy());
        if (photo != null) photo.setImageReference(model.phase == BdHomeViewModel.Phase.MANUAL ? model.draftImage : model.candidate == null ? null : model.candidate.image);
        if (photoCaption != null) photoCaption.setText(model.importing ? R.string.bd_dish_importing : model.draftImage == null ? R.string.bd_dish_pick_photo : R.string.bd_dish_photo_chosen);
        if (count != null) count.setText(model.ready ? getString(R.string.bd_dish_matching, model.matchingCount()) : getString(R.string.bd_dish_loading));
        if (primary != null) {
            int label = model.phase == BdHomeViewModel.Phase.CHOOSE ? R.string.bd_dish_idea
                    : model.phase == BdHomeViewModel.Phase.MANUAL ? R.string.bd_dish_manual_confirm
                    : model.phase == BdHomeViewModel.Phase.PREFERENCES ? R.string.bd_dish_blind_box
                    : model.phase == BdHomeViewModel.Phase.DISH ? R.string.bd_detail_recipe
                    : model.phase == BdHomeViewModel.Phase.MISSING_RECIPE ? R.string.bd_detail_add_recipe
                    : model.phase == BdHomeViewModel.Phase.EDIT_RECIPE ? R.string.bd_detail_save
                    : model.phase == BdHomeViewModel.Phase.NUTRITION ? R.string.bd_detail_ai_retry : R.string.bd_dish_accept;
            primary.setText(model.saving ? R.string.bd_dish_saving : label);
        }
    }

    private void updatePresentation() {
        boolean anchored = model.phase == BdHomeViewModel.Phase.DISH;
        presentation.setAnchored(anchored);
        Dialog dialog = getDialog();
        Window window = dialog == null ? null : dialog.getWindow();
        if (window == null || anchoredWindow != null && anchoredWindow == anchored) return;
        anchoredWindow = anchored;
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.gravity = anchored ? Gravity.TOP | Gravity.START : Gravity.CENTER;
        attributes.x = 0; attributes.y = 0; attributes.dimAmount = anchored ? 0f : .32f;
        if (anchored) attributes.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        else attributes.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        window.setAttributes(attributes);
        window.setLayout(anchored ? WindowManager.LayoutParams.MATCH_PARENT
                        : Math.min(dp(400), getResources().getDisplayMetrics().widthPixels - dp(28)),
                anchored ? WindowManager.LayoutParams.MATCH_PARENT : WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private void buildChoice() {
        title.setText(R.string.bd_dish_choose_title);
        primary = button(R.string.bd_dish_idea, true, R.id.bd_dish_primary);
        primary.setTextSize(19); primary.setOnClickListener(ignored -> model.manual());
        body.addView(primary, full(12));
        secondary = button(R.string.bd_dish_hungry, false, R.id.bd_dish_secondary);
        secondary.setOnClickListener(ignored -> model.preferences());
        body.addView(secondary, full(10));
    }

    private void buildManual() {
        title.setText(R.string.bd_dish_manual_title);
        TextInputLayout field = new TextInputLayout(requireContext());
        field.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        field.setBoxCornerRadii(dp(15), dp(15), dp(15), dp(15));
        field.setHint(getString(R.string.bd_dish_name_hint));
        TextInputEditText input = new TextInputEditText(field.getContext());
        input.setId(R.id.bd_dish_name_input); input.setSingleLine(true); input.setTextSize(17);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});
        input.setText(model.manualName); input.setSelection(input.length());
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { model.manualName = text.toString(); }
            @Override public void afterTextChanged(Editable text) { }
        });
        field.addView(input, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        body.addView(field, full(8));
        body.addView(text(getString(R.string.bd_dish_photo_label), 14, false), full(18));
        LinearLayout picker = new LinearLayout(requireContext());
        picker.setOrientation(LinearLayout.VERTICAL); picker.setGravity(Gravity.CENTER_HORIZONTAL);
        picker.setId(R.id.bd_dish_photo_input); picker.setClickable(true); picker.setFocusable(true);
        picker.setContentDescription(getString(R.string.bd_dish_photo_label));
        photo = new DishPlateView(requireContext()); photo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        picker.addView(photo, new LinearLayout.LayoutParams(dp(156), dp(156)));
        photoCaption = text(getString(R.string.bd_dish_pick_photo), 13, true);
        picker.addView(photoCaption, full(6));
        picker.setOnClickListener(ignored -> {
            if (!model.busy() && getParentFragment() instanceof BdHomeFragment) ((BdHomeFragment) getParentFragment()).chooseDishPhoto();
        });
        PressBounce.bind(picker);
        body.addView(picker, full(12));
        primary = button(R.string.bd_dish_manual_confirm, true, R.id.bd_dish_primary);
        primary.setOnClickListener(ignored -> {
            if (!model.manualName.trim().isEmpty()) {
                InputMethodManager keyboard = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
                input.clearFocus();
            }
            model.saveManual();
        });
        body.addView(primary, full(18));
    }

    private void buildPreferences() {
        title.setText(R.string.bd_dish_preferences_title);
        tastePreference(R.string.bd_dish_spicy, R.id.bd_dish_spicy_choice,
                new int[]{R.string.bd_dish_any_spicy, R.string.bd_dish_yes_spicy, R.string.bd_dish_no_spicy},
                new int[]{R.id.bd_dish_spicy_any, R.id.bd_dish_spicy_yes, R.id.bd_dish_spicy_no}, model.spicy, value -> model.spicy = value);
        tastePreference(R.string.bd_dish_meat, R.id.bd_dish_meat_choice,
                new int[]{R.string.bd_dish_any_meat, R.string.bd_dish_yes_meat, R.string.bd_dish_no_meat},
                new int[]{R.id.bd_dish_meat_any, R.id.bd_dish_meat_yes, R.id.bd_dish_meat_no}, model.meat, value -> model.meat = value);
        choicePreference(R.string.bd_dish_time, R.id.bd_dish_quick_choice,
                new int[]{R.string.bd_dish_any_time, R.string.bd_dish_quick},
                new int[]{R.id.bd_dish_quick_any, R.id.bd_dish_quick_yes}, model.quickOnly ? 1 : 0,
                value -> model.quickOnly = value == 1);
        count = text("", 13, true); body.addView(count, full(18));
        primary = button(R.string.bd_dish_blind_box, true, R.id.bd_dish_primary);
        primary.setOnClickListener(ignored -> model.drawFirst()); body.addView(primary, full(12));
    }

    private void tastePreference(int label, int id, int[] labels, int[] optionIds,
                                 BdRecipeCatalog.Taste selected, java.util.function.Consumer<BdRecipeCatalog.Taste> change) {
        choicePreference(label, id, labels, optionIds, selected.ordinal(),
                value -> change.accept(BdRecipeCatalog.Taste.values()[value]));
    }

    private void choicePreference(int label, int id, int[] labels, int[] optionIds,
                                  int selected, java.util.function.IntConsumer change) {
        TextView caption = text(getString(label), 17, false); caption.setLabelFor(id);
        body.addView(caption, full(12));
        ElasticChoiceView choice = new ElasticChoiceView(requireContext(), labels, optionIds, selected, value -> {
            change.accept(value); model.clearMessage();
        });
        choice.setId(id);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        size.topMargin = dp(8); body.addView(choice, size);
    }

    private void buildRecommendation() {
        title.setText(R.string.bd_dish_recommendation_title);
        photo = new DishPlateView(requireContext()); photo.setId(R.id.bd_dish_candidate_plate);
        LinearLayout.LayoutParams plate = new LinearLayout.LayoutParams(dp(192), dp(192));
        plate.gravity = Gravity.CENTER_HORIZONTAL; plate.topMargin = dp(10); body.addView(photo, plate);
        String name = model.candidate == null ? "" : model.candidate.name;
        photo.setContentDescription(name);
        TextView nameView = text(name, 23, true); nameView.setId(R.id.bd_dish_candidate_name);
        nameView.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(requireContext(), edu.xjtlu.civa.core.ui.R.font.civa_kuaile)); body.addView(nameView, full(12));
        MaterialButton details = button(R.string.bd_dish_details, false, R.id.bd_dish_details);
        details.setTextSize(13); body.addView(details, full(8));
        TextView method = text("", 14, false); method.setLineSpacing(dp(5), 1);
        if (model.candidate != null) method.setText(getString(R.string.bd_dish_ingredients) + "\n" + android.text.TextUtils.join("\n", model.candidate.ingredients)
                + "\n\n" + getString(R.string.bd_dish_steps) + "\n" + android.text.TextUtils.join("\n", model.candidate.steps)
                + "\n\n" + getString(R.string.bd_dish_source));
        method.setVisibility(View.GONE); body.addView(method, full(10));
        details.setOnClickListener(ignored -> { boolean expanded = method.getVisibility() != View.VISIBLE; method.setVisibility(expanded ? View.VISIBLE : View.GONE); details.setText(expanded ? R.string.bd_dish_hide_details : R.string.bd_dish_details); });
        LinearLayout actions = new LinearLayout(requireContext()); actions.setGravity(Gravity.CENTER_VERTICAL);
        primary = button(R.string.bd_dish_accept, true, R.id.bd_dish_primary);
        primary.setOnClickListener(ignored -> model.saveCandidate());
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1); left.setMarginEnd(dp(8));
        actions.addView(primary, left);
        secondary = button(R.string.bd_dish_reroll, false, R.id.bd_dish_secondary);
        secondary.setOnClickListener(ignored -> model.drawNext()); actions.addView(secondary, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        body.addView(actions, full(16));
    }


    private TextView heading(String value, int size, boolean centered) {
        TextView result = text(value, size, centered);
        result.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(requireContext(), edu.xjtlu.civa.core.ui.R.font.civa_kuaile));
        return result;
    }

    private void compactAction(MaterialButton action) {
        action.setTextSize(16); action.setMinHeight(dp(48)); action.setMinimumHeight(dp(48));
        action.setPadding(dp(8), 0, dp(8), 0); action.setInsetTop(0); action.setInsetBottom(0);
        action.setCornerRadius(dp(24));
        action.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xEAFFFBF2));
    }

    private void buildDishMenu() {
        title.setText(R.string.bd_detail_title);
        primary = button(R.string.bd_detail_recipe, false, R.id.bd_dish_primary); compactAction(primary);
        primary.setOnClickListener(ignored -> model.showRecipe()); body.addView(primary, full(2));
        body.addView(heading(getString(R.string.bd_detail_balance), 16, true), full(10));
        secondary = button(R.string.bd_detail_nutrition, false, R.id.bd_dish_secondary); compactAction(secondary);
        secondary.setOnClickListener(ignored -> model.showNutrition()); body.addView(secondary, full(4));
        removeButton = button(R.string.bd_detail_remove, true, R.id.bd_detail_remove); compactAction(removeButton);
        removeButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFAA5944));
        removeButton.setTextColor(ContextCompat.getColor(requireContext(), edu.xjtlu.civa.core.ui.R.color.civa_surface));
        removeButton.setOnClickListener(ignored -> model.removeDish()); body.addView(removeButton, full(8));
    }

    private void buildMissingRecipe() {
        title.setText(R.string.bd_detail_missing);
        primary = button(R.string.bd_detail_add_recipe, true, R.id.bd_dish_primary);
        primary.setOnClickListener(ignored -> model.editRecipe()); body.addView(primary, full(20));
    }

    private void buildRecipe() {
        title.setText(R.string.bd_detail_recipe);
        BdDraft.Dish dish = model.selectedDish();
        if (dish == null) return;
        body.addView(heading(dish.name, 24, true), full(12));
        LinearLayout contents = new LinearLayout(requireContext()); contents.setOrientation(LinearLayout.VERTICAL);
        contents.setId(R.id.bd_detail_recipe_text);
        contents.addView(heading(getString(R.string.bd_detail_ingredients), 22, false), full(20));
        TextView ingredients = text(android.text.TextUtils.join("\n", dish.ingredients), 15, false);
        ingredients.setLineSpacing(dp(5), 1); contents.addView(ingredients, full(10));
        contents.addView(heading(getString(R.string.bd_detail_steps), 22, false), full(24));
        TextView steps = text(android.text.TextUtils.join("\n", dish.steps), 15, false);
        steps.setLineSpacing(dp(5), 1); contents.addView(steps, full(10)); body.addView(contents, full(0));
        TextView source = text(getString(dish.sourceUrl == null ? R.string.bd_detail_personal_recipe : R.string.bd_dish_source), 11, true);
        source.setTextColor(ContextCompat.getColor(requireContext(), edu.xjtlu.civa.core.ui.R.color.civa_muted)); body.addView(source, full(24));
    }

    private void buildRecipeEditor() {
        title.setText(R.string.bd_detail_edit_title);
        body.addView(heading(getString(R.string.bd_detail_ingredients), 22, false), full(12));
        recipeField(R.id.bd_detail_ingredients, R.string.bd_detail_ingredients_hint, model.recipeIngredientsDraft, value -> model.recipeIngredientsDraft = value);
        body.addView(heading(getString(R.string.bd_detail_steps), 22, false), full(20));
        recipeField(R.id.bd_detail_steps, R.string.bd_detail_steps_hint, model.recipeStepsDraft, value -> model.recipeStepsDraft = value);
        primary = button(R.string.bd_detail_save, true, R.id.bd_dish_primary);
        primary.setOnClickListener(ignored -> {
            View focus = requireDialog().getCurrentFocus();
            InputMethodManager keyboard = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (keyboard != null && focus != null) keyboard.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            model.saveRecipe();
        });
        body.addView(primary, full(20));
    }

    private void recipeField(int id, int hint, String initial, TextChange change) {
        TextInputLayout field = new TextInputLayout(requireContext()); field.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        field.setBoxCornerRadii(dp(15), dp(15), dp(15), dp(15)); field.setHint(getString(hint));
        TextInputEditText input = new TextInputEditText(field.getContext()); input.setId(id);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setGravity(Gravity.TOP | Gravity.START); input.setTextSize(15); input.setMinLines(3); input.setMaxLines(6);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8000)}); input.setText(initial);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { change.set(value.toString()); }
            @Override public void afterTextChanged(Editable value) { }
        });
        field.addView(input, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        body.addView(field, full(10));
    }

    private void buildNutrition() {
        BdDraft.Dish dish = model.selectedDish();
        title.setText(dish == null ? getString(R.string.bd_detail_nutrition) : dish.name);
        TextView basis = text(getString(R.string.bd_detail_ai_basis), 12, true);
        basis.setTextColor(ContextCompat.getColor(requireContext(), edu.xjtlu.civa.core.ui.R.color.civa_muted)); body.addView(basis, full(10));
        if (model.nutritionLoading) {
            android.widget.ProgressBar progress = new android.widget.ProgressBar(requireContext());
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(32), dp(32)); size.gravity = Gravity.CENTER_HORIZONTAL; size.topMargin = dp(32);
            body.addView(progress, size); body.addView(text(getString(R.string.bd_detail_ai_loading), 15, true), full(18));
            return;
        }
        BdNutrition value = model.nutrition;
        if (value == null) {
            primary = button(R.string.bd_detail_ai_retry, true, R.id.bd_dish_primary);
            primary.setOnClickListener(ignored -> model.showNutrition()); body.addView(primary, full(20)); return;
        }
        TextView energy = heading(value.energyKcal == null ? getString(R.string.bd_detail_unknown)
                : getString(R.string.bd_detail_energy, decimal(value.energyKcal)), 38, true);
        energy.setId(R.id.bd_detail_nutrition); body.addView(energy, full(18));
        nutrientRow(new int[]{R.string.bd_detail_carbs, R.string.bd_detail_protein, R.string.bd_detail_fat}, new Double[]{value.carbsG, value.proteinG, value.fatG}, false);
        nutrientRow(new int[]{R.string.bd_detail_fiber, R.string.bd_detail_sugars, R.string.bd_detail_salt}, new Double[]{value.fiberG, value.sugarsG, value.saltMg}, true);
        LinearLayout tips = new LinearLayout(requireContext()); tips.setOrientation(LinearLayout.VERTICAL); tips.setPadding(dp(16), dp(16), dp(16), dp(16));
        tips.setBackgroundResource(R.drawable.bd_home_lunch_card);
        tips.addView(heading(getString(R.string.bd_detail_ai_tips), 22, false));
        TextView content = text(value.tips, 15, false); content.setLineSpacing(dp(6), 1); tips.addView(content, full(12));
        body.addView(tips, full(28));
        TextView note = text(getString(R.string.bd_detail_ai_note), 11, false);
        note.setTextColor(ContextCompat.getColor(requireContext(), edu.xjtlu.civa.core.ui.R.color.civa_muted)); body.addView(note, full(14));
    }

    private void nutrientRow(int[] labels, Double[] values, boolean lastIsSalt) {
        LinearLayout row = new LinearLayout(requireContext()); row.setOrientation(LinearLayout.HORIZONTAL); row.setBaselineAligned(false);
        for (int i = 0; i < 3; i++) {
            LinearLayout cell = new LinearLayout(requireContext()); cell.setOrientation(LinearLayout.VERTICAL); cell.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView label = text(getString(labels[i]), 13, true);
            label.setTextColor(ContextCompat.getColor(requireContext(), edu.xjtlu.civa.core.ui.R.color.civa_muted)); cell.addView(label);
            String amount = values[i] == null ? getString(R.string.bd_detail_unknown) : getString(lastIsSalt && i == 2 ? R.string.bd_detail_milligrams : R.string.bd_detail_grams, decimal(values[i]));
            cell.addView(heading(amount, 20, true), full(8));
            row.addView(cell, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        }
        body.addView(row, full(24));
    }

    private String decimal(Double value) {
        java.text.DecimalFormat format = new java.text.DecimalFormat("0.#", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.CHINA));
        return format.format(value);
    }

    private interface TextChange { void set(String value); }

    private MaterialButton button(int text, boolean filled, int id) {
        MaterialButton button = (MaterialButton) getLayoutInflater().inflate(filled ? R.layout.bd_dish_button_primary : R.layout.bd_dish_button_secondary, body, false);
        button.setText(text); button.setId(id); PressBounce.bind(button); return button;
    }

    private TextView text(String value, int size, boolean centered) {
        TextView result = new TextView(requireContext()); result.setText(value); result.setTextSize(size);
        result.setTextColor(ContextCompat.getColor(requireContext(), edu.xjtlu.civa.core.ui.R.color.civa_text));
        if (centered) result.setGravity(Gravity.CENTER);
        return result;
    }

    private LinearLayout.LayoutParams full(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top); return params;
    }

    @Override public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        if (getParentFragment() instanceof BdHomeFragment) ((BdHomeFragment) getParentFragment()).flowDismissed();
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    public static final class DialogScroll extends ScrollView {
        private boolean preferences;
        public DialogScroll(Context context, AttributeSet attrs) { super(context, attrs); }
        void setPreferences(boolean value) {
            if (preferences != value) { preferences = value; requestLayout(); }
        }
        @Override protected void onMeasure(int width, int height) {
            // Three equal-height selector rows need a little more room in short, wide windows.
            int max = Math.round(getResources().getDisplayMetrics().heightPixels * (preferences ? .70f : .64f));
            int supplied = View.MeasureSpec.getSize(height);
            if (View.MeasureSpec.getMode(height) != View.MeasureSpec.UNSPECIFIED) max = Math.min(max, supplied);
            super.onMeasure(width, View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST));
        }
    }
}
