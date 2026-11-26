package edu.xjtlu.civa.feature.bd;

import edu.xjtlu.civa.core.ui.ImageSources;
import edu.xjtlu.civa.core.ui.PressBounce;
import edu.xjtlu.civa.core.ui.DishPlateView;
import edu.xjtlu.civa.core.data.SessionStore;

import android.os.Bundle;
import android.graphics.Rect;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.datepicker.MaterialDatePicker;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

public final class BdHomeFragment extends Fragment {
    private static final String CALENDAR = "app.home.calendar", FLOW = "app.home.addDish";
    private BdHomeViewModel model;
    private String rendered, shownError;
    private boolean flowOpening;
    private final ActivityResultLauncher<String[]> photoPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> { if (model != null && uri != null) model.importPhoto(uri); });

    public BdHomeFragment() { super(R.layout.bd_fragment_home); }

    @Override public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Dish plates live in core-ui and resolve this feature's photo references through a hook.
        ImageSources.setLoader(BdPhotoFiles::open);
        BdHost host = requireActivity() instanceof BdHost ? (BdHost) requireActivity() : null;
        model = new ViewModelProvider(requireActivity(), new ViewModelProvider.Factory() {
            @NonNull @Override public <T extends androidx.lifecycle.ViewModel> T create(@NonNull Class<T> type) {
                if (type != BdHomeViewModel.class) throw new IllegalArgumentException("Unknown ViewModel");
                return type.cast(new BdHomeViewModel(requireActivity().getApplication(),
                        host == null ? null : host.api(), host == null ? null : host.calls()));
            }
        }).get(BdHomeViewModel.class);
        String owner = "unavailable";
        String[] legacyOwners = new String[0];
        if (host != null) {
            SessionStore sessions = host.sessions();
            owner = sessions.isLoggedIn() ? "account:" + sessions.currentUserId() : "signed-out";
            if (sessions.isLoggedIn()) legacyOwners = new String[]{owner + "/" + sessions.currentHouseholdId(), owner + "/null"};
        }
        model.initialize(owner, savedInstanceState, legacyOwners);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        rendered = null;
        PressBounce.bindButtons(view);
        view.findViewById(R.id.bd_home_calendar).setOnClickListener(ignored -> showCalendar());
        view.findViewById(R.id.bd_home_plan).setOnClickListener(ignored ->
                ((BdHost) requireActivity()).navigate(edu.xjtlu.civa.core.ui.Route.PLAN, null));
        model.changes.observe(getViewLifecycleOwner(), ignored -> render());
        Fragment restoredPicker = getChildFragmentManager().findFragmentByTag(CALENDAR);
        if (restoredPicker instanceof MaterialDatePicker<?>) bindCalendar((MaterialDatePicker<?>) restoredPicker);
    }

    private void render() {
        View view = getView();
        if (view == null) return;
        String weekday = getResources().getStringArray(R.array.bd_l10n_weekdays)[model.date.getDayOfWeek().getValue() - 1];
        ((TextView) view.findViewById(R.id.bd_home_weekday)).setText(weekday);
        ((TextView) view.findViewById(R.id.bd_home_weekday_en)).setText(model.date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
        ((TextView) view.findViewById(R.id.bd_home_date)).setText(model.date.format(DateTimeFormatter.ofPattern("yyyy.MM.dd", Locale.ROOT)));
        view.findViewById(R.id.bd_home_calendar).setContentDescription(getString(R.string.bd_home_selected_date, model.date.toString(), weekday));
        String signature = model.date + "/" + model.planVersion + "/" + model.ready;
        if (!signature.equals(rendered)) {
            renderMeal(view, "BREAKFAST", R.id.bd_home_breakfast_items, R.id.bd_home_add_breakfast);
            renderMeal(view, "LUNCH", R.id.bd_home_lunch_items, R.id.bd_home_add_lunch);
            renderMeal(view, "DINNER", R.id.bd_home_dinner_items, R.id.bd_home_add_dinner);
            rendered = signature;
            if (model.phase == BdHomeViewModel.Phase.DISH) view.post(this::revealSelectedDish);
        }
        if (!model.ready && model.message != null && !model.message.equals(shownError)) {
            shownError = model.message;
            Toast.makeText(requireContext(), model.message, Toast.LENGTH_LONG).show();
        }
    }

    private void renderMeal(View root, String meal, int itemsId, int buttonId) {
        LinearLayout items = root.findViewById(itemsId);
        MaterialButton add = root.findViewById(buttonId);
        items.removeAllViews();
        List<BdDraft.Dish> dishes = model.dishes(meal);
        for (BdDraft.Dish dish : dishes) {
            LinearLayout card = new LinearLayout(requireContext());
            card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setClickable(true); card.setFocusable(true);
            card.setContentDescription(getString(R.string.bd_detail_open_description, dish.name));
            card.setBackgroundResource(edu.xjtlu.civa.core.ui.R.drawable.civa_dish_tile_ripple);
            card.setTag(R.id.bd_dish_anchor_id, dish.id);
            card.setOnClickListener(ignored -> openDish(meal, dish.id));
            PressBounce.bind(card);
            DishPlateView plate = new DishPlateView(requireContext());
            plate.setImageReference(dish.image);
            plate.setContentDescription(getString(R.string.bd_dish_plate_description, dish.name));
            card.addView(plate, new LinearLayout.LayoutParams(dp(68), 0, 1));
            TextView name = new TextView(requireContext());
            name.setText(dish.name); name.setTextSize(12); name.setGravity(Gravity.CENTER);
            name.setTextColor(ContextCompat.getColor(requireContext(), edu.xjtlu.civa.core.ui.R.color.civa_text));
            name.setMaxLines(1); name.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams text = new LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.WRAP_CONTENT);
            text.topMargin = dp(3); card.addView(name, text);
            LinearLayout.LayoutParams tile = new LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.MATCH_PARENT);
            tile.setMarginEnd(dp(8)); items.addView(card, tile);
        }
        add.setEnabled(model.ready);
        add.setOnClickListener(ignored -> openAdd(meal));
        ((View) items.getParent()).setVisibility(dishes.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void openAdd(String meal) {
        if (!model.ready || flowOpening || getChildFragmentManager().isStateSaved()
                || getChildFragmentManager().findFragmentByTag(FLOW) != null) return;
        model.begin(meal);
        flowOpening = true;
        new BdFlowDialog().show(getChildFragmentManager(), FLOW);
    }

    private void openDish(String meal, String id) {
        if (!model.ready || flowOpening || getChildFragmentManager().isStateSaved()
                || getChildFragmentManager().findFragmentByTag(FLOW) != null) return;
        model.openDish(meal, id); revealSelectedDish(); flowOpening = true;
        new BdFlowDialog().show(getChildFragmentManager(), FLOW);
    }

    @Nullable private View selectedDishCard() {
        View root = getView();
        if (root == null || model.selectedDishId == null || model.targetDate == null || !model.targetDate.equals(model.date)) return null;
        int itemsId = "LUNCH".equals(model.targetMeal) ? R.id.bd_home_lunch_items
                : "DINNER".equals(model.targetMeal) ? R.id.bd_home_dinner_items : R.id.bd_home_breakfast_items;
        LinearLayout items = root.findViewById(itemsId);
        for (int i = 0; i < items.getChildCount(); i++) {
            View card = items.getChildAt(i);
            if (model.selectedDishId.equals(card.getTag(R.id.bd_dish_anchor_id))) return card;
        }
        return null;
    }

    private void revealSelectedDish() {
        View card = selectedDishCard();
        if (card != null) card.requestRectangleOnScreen(new Rect(0, 0, card.getWidth(), card.getHeight()), true);
    }

    @Nullable Rect selectedDishAnchorBounds() {
        View card = selectedDishCard();
        if (!(card instanceof LinearLayout)) return null;
        View plate = ((LinearLayout) card).getChildAt(0);
        Rect visible = new Rect();
        if (!plate.getGlobalVisibleRect(visible) || visible.width() < dp(24)) return null;
        int[] rootOrigin = new int[2]; plate.getRootView().getLocationOnScreen(rootOrigin);
        visible.offset(rootOrigin[0], rootOrigin[1]);
        return visible;
    }

    void chooseDishPhoto() { photoPicker.launch(new String[]{"image/*"}); }
    void flowDismissed() { flowOpening = false; }

    private void showCalendar() {
        if (getChildFragmentManager().findFragmentByTag(CALENDAR) != null) return;
        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setTheme(R.style.BdTheme_HomeCalendar).setTitleText(R.string.bd_home_choose_date)
                .setPositiveButtonText(R.string.bd_home_confirm_date).setNegativeButtonText(R.string.bd_home_cancel)
                .setSelection(model.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()).build();
        bindCalendar(picker); picker.show(getChildFragmentManager(), CALENDAR);
    }

    private void bindCalendar(MaterialDatePicker<?> picker) {
        picker.clearOnPositiveButtonClickListeners();
        picker.addOnPositiveButtonClickListener(selection -> {
            if (selection instanceof Long) model.selectDate(Instant.ofEpochMilli((Long) selection).atZone(ZoneOffset.UTC).toLocalDate());
        });
    }

    @Override public void onSaveInstanceState(@NonNull Bundle outState) { super.onSaveInstanceState(outState); model.saveState(outState); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
