package edu.xjtlu.civa.feature.ac.inventory;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import edu.xjtlu.civa.api.model.*;
import edu.xjtlu.civa.core.ui.Route;
import edu.xjtlu.civa.feature.ac.AcMessage;
import edu.xjtlu.civa.feature.ac.AcText;
import edu.xjtlu.civa.feature.ac.AcValidationException;
import edu.xjtlu.civa.feature.ac.R;
import edu.xjtlu.civa.feature.ac.databinding.AcFragmentInventoryBinding;
import java.math.BigDecimal;
import java.util.*;

/** Native Views implementation of the user-approved fridge interaction demo. */
public final class InventoryFragment extends Fragment {
  private AcFragmentInventoryBinding binding;
  private InventoryViewModel model;
  private final List<androidx.appcompat.app.AlertDialog> dialogs = new ArrayList<>();
  private FrameLayout coldFrame, freezeFrame;
  private View coldDoor, freezeDoor;
  private MaterialButton coldControl, freezeControl;
  private BottomSheetDialog detailDialog;
  private LinearLayout sheetDetail;
  private int workspaceHeight, workspaceWidth;
  private String presentedId;
  private List<InventoryGroups.Group> foodGroups = Collections.emptyList();

  public InventoryFragment() {
    super(R.layout.ac_fragment_inventory);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
    InventoryHost host = (InventoryHost) requireActivity();
    model =
        new ViewModelProvider(
                this,
                new ViewModelProvider.Factory() {
                  @NonNull
                  @Override
                  public <T extends ViewModel> T create(@NonNull Class<T> type) {
                    return type.cast(
                        new InventoryViewModel(host.api(), host.calls(), host.sessions()));
                  }
                })
            .get(InventoryViewModel.class);
    binding = AcFragmentInventoryBinding.bind(view);
    if (state != null) {
      model.coldOpen = state.getBoolean("cold", model.coldOpen);
      model.freezeOpen = state.getBoolean("freeze", model.freezeOpen);
      model.selectedId = state.getString("selected", model.selectedId);
      model.filter = state.getString("filter", model.filter);
      model.draftName = state.getString("draftName", "");
      model.draftUnitIndex = state.getInt("draftUnitIndex", 0);
      model.draftQuantity = state.getString("draftQuantity", "");
      model.draftExpiry = state.getString("draftExpiry", "");
      model.draftIngredient = state.getInt("draftIngredient");
      model.draftLocation = state.getInt("draftLocation", 1);
    }
    presentedId = model.selectedId;
    binding.acAdd.setOnClickListener(v -> requestAddFood());
    binding.acMessage.setOnClickListener(
        v -> {
          if (!model.hasHousehold()) requestAddFood();
          else
            new MaterialAlertDialogBuilder(requireContext())
                .setMessage(localized(model.message))
                .setPositiveButton(R.string.ac_l10n_ack, null)
                .show();
        });
    binding.acWorkspace.addOnLayoutChangeListener(
        (v, l, t, r, b, ol, ot, or, ob) -> {
          if (r - l != workspaceWidth || b - t != workspaceHeight) {
            workspaceWidth = r - l;
            workspaceHeight = b - t;
            render();
          }
        });
    binding.acRefresh.setOnClickListener(v -> model.load());
    binding.acOpen.setOnClickListener(
        v -> {
          model.coldOpen = !(model.coldOpen && model.freezeOpen);
          model.freezeOpen = model.coldOpen;
          animateDoor(coldDoor, model.coldOpen);
          animateDoor(freezeDoor, model.freezeOpen);
          updateOpenLabel();
        });
    binding.acRetryWrite.setOnClickListener(v -> model.retry());
    binding.acAbandonWrite.setOnClickListener(
        v ->
            new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ac_l10n_inventory_end_pending_title)
                .setMessage(R.string.ac_l10n_inventory_end_pending_message)
                .setNegativeButton(R.string.ac_l10n_inventory_end_pending_continue, null)
                .setPositiveButton(
                    R.string.ac_l10n_inventory_end_pending_confirm, (d, w) -> model.abandon())
                .show());
    String[] labels = {
      getString(R.string.ac_l10n_inventory_filter_all),
      getString(R.string.ac_l10n_inventory_filter_valid),
      getString(R.string.ac_l10n_inventory_filter_soon),
      getString(R.string.ac_l10n_inventory_filter_expired)
    };
    String[] values = {"ALL", "VALID", "SOON", "EXPIRED"};
    binding.acFilters.removeAllViews();
    for (int i = 0; i < labels.length; i++) {
      String value = values[i];
      Chip chip = new Chip(requireContext());
      chip.setText(labels[i]);
      chip.setCheckable(true);
      chip.setTag(value);
      chip.setChipBackgroundColor(
          new ColorStateList(
              new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
              new int[] {color(R.color.ac_fridge_selected), color(R.color.ac_fridge_panel)}));
      chip.setTextColor(color(R.color.ac_fridge_ink));
      chip.setChipStrokeColor(ColorStateList.valueOf(color(R.color.ac_fridge_line)));
      chip.setChipStrokeWidth(dp(1));
      chip.setCheckedIconTint(ColorStateList.valueOf(color(R.color.ac_fridge_accent)));
      chip.setChecked(model.filter.equals(value));
      chip.setOnClickListener(
          v -> {
            for (int n = 0; n < binding.acFilters.getChildCount(); n++)
              ((Chip) binding.acFilters.getChildAt(n))
                  .setChecked(binding.acFilters.getChildAt(n) == chip);
            model.filter(value);
          });
      binding.acFilters.addView(chip);
    }
    model.revision.observe(getViewLifecycleOwner(), ignored -> render());
    model.load();
  }

  @Override
  public void onResume() {
    super.onResume();
    if (model != null && model.scopeChanged()) model.load();
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle state) {
    super.onSaveInstanceState(state);
    if (model == null) return;
    state.putBoolean("cold", model.coldOpen);
    state.putBoolean("freeze", model.freezeOpen);
    state.putString("selected", model.selectedId);
    state.putString("filter", model.filter);
    state.putString("draftName", model.draftName);
    state.putInt("draftUnitIndex", model.draftUnitIndex);
    state.putString("draftQuantity", model.draftQuantity);
    state.putString("draftExpiry", model.draftExpiry);
    state.putInt("draftIngredient", model.draftIngredient);
    state.putInt("draftLocation", model.draftLocation);
  }

  @Override
  public void onDestroyView() {
    if (detailDialog != null) detailDialog.dismiss();
    for (var dialog : new ArrayList<>(dialogs)) dialog.dismiss();
    dialogs.clear();
    coldDoor = null;
    freezeDoor = null;
    coldControl = null;
    freezeControl = null;
    binding = null;
    super.onDestroyView();
  }

  private void render() {
    if (binding == null) return;
    String visibleMessage = localized(model.message);
    binding.acMessage.setText(visibleMessage);
    binding.acMessage.setVisibility(visibleMessage.isEmpty() ? View.GONE : View.VISIBLE);
    binding.acLoading.setVisibility(model.loading ? View.VISIBLE : View.GONE);
    binding.acRecovery.setVisibility(model.pending != null ? View.VISIBLE : View.GONE);
    binding.acRetryWrite.setEnabled(!model.busy);
    binding.acAbandonWrite.setEnabled(!model.busy);
    binding.acAdd.setEnabled(!model.busy);
    binding.acAdd.setText(model.busy ? getString(R.string.ac_l10n_saving) : getString(R.string.ac_add_food));
    binding.acRefresh.setEnabled(!model.busy);
    for (int i = 0; i < binding.acFilters.getChildCount(); i++) {
      Chip chip = (Chip) binding.acFilters.getChildAt(i);
      chip.setEnabled(!model.busy);
      chip.setChecked(model.filter.equals(chip.getTag()));
    }
    boolean wide = getResources().getConfiguration().screenWidthDp >= 720;
    binding.acRefresh.setTextColor(color(R.color.ac_fridge_muted));
    binding.acActions.setOrientation(LinearLayout.HORIZONTAL);
    binding.acWorkspace.setOrientation(LinearLayout.HORIZONTAL);
    binding.acWorkspace.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    binding.acFridge.setLayoutParams(
        new LinearLayout.LayoutParams(
            wide
                ? dp(Math.min(450, getResources().getConfiguration().screenWidthDp - 310))
                : ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
    binding.acDetailScroll.setVisibility(wide ? View.VISIBLE : View.GONE);
    LinearLayout.LayoutParams detailParams =
        new LinearLayout.LayoutParams(dp(270), ViewGroup.LayoutParams.MATCH_PARENT);
    detailParams.leftMargin = dp(8);
    binding.acDetailScroll.setLayoutParams(detailParams);
    foodGroups = InventoryGroups.from(model.batches);
    buildFridge();
    buildDetail();
    if (detailDialog != null && !model.loading && model.selectedId == null) detailDialog.dismiss();
    if (sheetDetail != null) fillDetail(sheetDetail);
    if (!wide && model.selected() != null && !Objects.equals(presentedId, model.selectedId))
      showDetail();
    updateOpenLabel();
  }

  private void buildFridge() {
    binding.acFridge.removeAllViews();
    coldFrame =
        compartment(getString(R.string.ac_l10n_inventory_chill_room), new int[] {0, 1, 2}, false);
    freezeFrame =
        compartment(
            getString(R.string.ac_l10n_inventory_freeze_room), new int[] {3, 4}, true);
    coldFrame.setId(R.id.ac_cold_compartment);
    freezeFrame.setId(R.id.ac_frozen_compartment);
    binding.acFridge.addView(coldFrame, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
    lp.topMargin = dp(6);
    binding.acFridge.addView(freezeFrame, lp);
    LinearLayout controls = new LinearLayout(requireContext());
    controls.setGravity(Gravity.CENTER);
    MaterialButton cold = button(doorLabel(false), true), freeze = button(doorLabel(true), true);
    coldControl = cold;
    freezeControl = freeze;
    cold.setOnClickListener(
        v -> {
          model.coldOpen = !model.coldOpen;
          animateDoor(coldDoor, model.coldOpen);
          cold.setText(doorLabel(false));
          updateOpenLabel();
        });
    freeze.setOnClickListener(
        v -> {
          model.freezeOpen = !model.freezeOpen;
          animateDoor(freezeDoor, model.freezeOpen);
          freeze.setText(doorLabel(true));
          updateOpenLabel();
        });
    controls.addView(cold, new LinearLayout.LayoutParams(0, -2, 1));
    controls.addView(freeze, new LinearLayout.LayoutParams(0, -2, 1));
    binding.acFridge.addView(controls, new LinearLayout.LayoutParams(-1, dp(40)));
    List<InventoryBatch> elsewhere = new ArrayList<>();
    for (var group : foodGroups) if (group.shelf() < 0) elsewhere.addAll(group.batches);
    if (!elsewhere.isEmpty()) {
      TextView other =
          text(getString(R.string.ac_l10n_inventory_other_locations, elsewhere.size()), 12);
      other.setOnClickListener(
          v -> showBatches(getString(R.string.ac_l10n_inventory_other_storage), elsewhere));
      binding.acFridge.addView(other, new LinearLayout.LayoutParams(-1, dp(32)));
    }
  }

  /** Door state label used by the two door buttons; state words come from resources. */
  private String doorLabel(boolean freezer) {
    if (freezer)
      return getString(
          model.freezeOpen
              ? R.string.ac_l10n_inventory_door_freeze_open
              : R.string.ac_l10n_inventory_door_freeze_closed);
    return getString(
        model.coldOpen
            ? R.string.ac_l10n_inventory_door_cold_open
            : R.string.ac_l10n_inventory_door_cold_closed);
  }

  private FrameLayout compartment(String title, int[] shelves, boolean freezer) {
    FrameLayout frame = new FrameLayout(requireContext());
    GradientDrawable lining =
        gradient(R.color.ac_fridge_line, R.color.ac_fridge_panel, R.color.ac_fridge_line, 20);
    lining.setColors(
        new int[] {
          color(R.color.ac_fridge_line),
          color(R.color.ac_fridge_panel),
          color(R.color.ac_fridge_panel),
          color(R.color.ac_fridge_panel),
          color(R.color.ac_fridge_panel),
          color(R.color.ac_fridge_panel),
          color(R.color.ac_fridge_panel),
          color(R.color.ac_fridge_panel),
          color(R.color.ac_fridge_line)
        });
    frame.setBackground(lining);
    frame.setClipToOutline(true);
    LinearLayout inside = column();
    inside.setPadding(dp(8), dp(4), dp(8), dp(4));
    LinearLayout sectionTitle = new LinearLayout(requireContext());
    sectionTitle.setGravity(Gravity.CENTER_VERTICAL);
    sectionTitle.addView(text(title, 11), new LinearLayout.LayoutParams(0, -2, 1));
    TextView temperature = text(freezer ? "−18°C  ●" : "4°C  ●", 12);
    temperature.setTextColor(color(R.color.ac_fridge_accent));
    sectionTitle.addView(temperature);
    inside.addView(sectionTitle);
    String[] names = {
      getString(R.string.ac_l10n_inventory_shelf_dairy),
      getString(R.string.ac_l10n_inventory_shelf_veg),
      getString(R.string.ac_l10n_inventory_shelf_fruit),
      getString(R.string.ac_l10n_inventory_shelf_meat),
      getString(R.string.ac_l10n_inventory_shelf_seafood)
    };
    LinearLayout drawers = new LinearLayout(requireContext());
    if (freezer) inside.addView(drawers, new LinearLayout.LayoutParams(-1, 0, 1));
    for (int index : shelves) {
      LinearLayout area = column();
      if (freezer) {
        area.setPadding(dp(7), 0, dp(7), dp(6));
        GradientDrawable drawer = round(R.color.ac_fridge_drawer, 12);
        drawer.setStroke(dp(1), color(R.color.ac_fridge_line));
        area.setBackground(drawer);
        LinearLayout.LayoutParams drawerParams = new LinearLayout.LayoutParams(0, -1, 1);
        drawerParams.leftMargin = dp(index == 4 ? 8 : 0);
        drawers.addView(area, drawerParams);
      } else {
        inside.addView(area, new LinearLayout.LayoutParams(-1, 0, 1));
      }
      TextView label = text(names[index], 11);
      label.setTextColor(color(R.color.ac_fridge_muted));
      label.setPadding(0, dp(2), 0, 0);
      List<InventoryBatch> items = new ArrayList<>();
      List<InventoryGroups.Group> groups = new ArrayList<>();
      for (var group : foodGroups)
        if (group.shelf() == index) {
          groups.add(group);
          items.addAll(group.batches);
        }
      label.setText(
          names[index]
              + (groups.size() > 3
                  ? getString(R.string.ac_l10n_inventory_shelf_more, groups.size())
                  : ""));
      label.setOnClickListener(v -> showBatches(names[index], items));
      label.setContentDescription(
          getString(R.string.ac_l10n_inventory_shelf_description, names[index]));
      area.addView(label);
      if (items.isEmpty()) {
        TextView empty = text(getString(R.string.ac_l10n_inventory_empty_shelf), 13);
        empty.setGravity(Gravity.CENTER);
        area.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1));
      }
      if (freezer) {
        for (var group : groups.subList(0, Math.min(3, groups.size())))
          area.addView(frozenTile(group), new LinearLayout.LayoutParams(-1, 0, 1));
      } else if (!items.isEmpty()) {
        LinearLayout row = new LinearLayout(requireContext());
        for (int n = 0; n < 3; n++) {
          View tile = n < groups.size() ? foodTile(groups.get(n)) : new View(requireContext());
          row.addView(tile, new LinearLayout.LayoutParams(0, -1, 1));
        }
        area.addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
      }
      View shelf = new View(requireContext());
      shelf.setBackgroundColor(color(R.color.ac_fridge_shelf));
      LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, dp(3));
      line.topMargin = dp(2);
      if (freezer || index != 2) area.addView(shelf, line);
    }
    frame.addView(inside, new FrameLayout.LayoutParams(-1, -1));
    FrameLayout door = new FrameLayout(requireContext());
    door.setPadding(dp(26), dp(26), dp(26), dp(26));
    door.setBackground(
        gradient(
            R.color.ac_fridge_door_light, R.color.ac_fridge_door, R.color.ac_fridge_door_dark, 20));
    TextView brand =
        text(
            freezer ? getString(R.string.ac_l10n_inventory_brand_freezer) : "civa",
            freezer ? 16 : 36);
    brand.setTypeface(ResourcesCompat.getFont(requireContext(), R.font.ac_fridge_brand));
    door.addView(brand, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
    if (!freezer) {
      TextView note = text("FRESH EVERY DAY", 9);
      note.setLetterSpacing(.18f);
      note.setAlpha(.6f);
      FrameLayout.LayoutParams noteParams = new FrameLayout.LayoutParams(-2, -2);
      noteParams.topMargin = dp(52);
      door.addView(note, noteParams);
      TextView display = text(getString(R.string.ac_l10n_inventory_display_cold), 24);
      display.setGravity(Gravity.CENTER);
      door.addView(display, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));
    }
    TextView hint =
        text(
            freezer
                ? getString(R.string.ac_l10n_inventory_hint_freezer)
                : getString(R.string.ac_l10n_inventory_hint_cold),
            12);
    door.addView(hint, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));
    View handle = new View(requireContext());
    handle.setBackground(
        gradient(
            R.color.ac_fridge_accent, R.color.ac_fridge_panel, R.color.ac_fridge_door_light, 7));
    handle.setElevation(dp(3));
    FrameLayout.LayoutParams handleLp =
        new FrameLayout.LayoutParams(
            dp(10), dp(freezer ? 65 : 78), Gravity.END | Gravity.CENTER_VERTICAL);
    door.addView(handle, handleLp);
    frame.addView(door, new FrameLayout.LayoutParams(-1, -1));
    door.setContentDescription(
        getString(
            freezer
                ? R.string.ac_l10n_inventory_cd_open_freezer
                : R.string.ac_l10n_inventory_cd_open_cold));
    door.setClickable(true);
    door.setFocusable(true);
    boolean open = freezer ? model.freezeOpen : model.coldOpen;
    door.setVisibility(open ? View.GONE : View.VISIBLE);
    inside.setImportantForAccessibility(
        open
            ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    door.setOnClickListener(
        v -> {
          if (freezer) model.freezeOpen = true;
          else model.coldOpen = true;
          animateDoor(door, true);
          updateOpenLabel();
        });
    if (freezer) freezeDoor = door;
    else coldDoor = door;
    return frame;
  }

  private void animateDoor(View door, boolean open) {
    if (door == null) return;
    door.animate().cancel();
    door.setCameraDistance(dp(1800));
    door.setPivotX(0);
    door.setVisibility(View.VISIBLE);
    door.setClickable(!open);
    door.setFocusable(!open);
    View inside = ((ViewGroup) door.getParent()).getChildAt(0);
    inside.setImportantForAccessibility(
        open
            ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    door.animate()
        .rotationY(open ? -95 : 0)
        .alpha(open ? 0 : 1)
        .setDuration(450)
        .setListener(
            new AnimatorListenerAdapter() {
              @Override
              public void onAnimationEnd(Animator a) {
                if (open) door.setVisibility(View.GONE);
              }
            })
        .start();
  }

  private void updateOpenLabel() {
    if (binding != null)
      binding.acOpen.setText(
          model.coldOpen && model.freezeOpen
              ? R.string.ac_l10n_inventory_close_fridge
              : R.string.ac_open_fridge);
    if (coldControl != null) coldControl.setText(doorLabel(false));
    if (freezeControl != null) freezeControl.setText(doorLabel(true));
  }

  private View foodTile(InventoryGroups.Group group) {
    InventoryBatch batch = group.first();
    Ingredient ingredient = model.ingredient(batch);
    String name = InventoryPresentation.name(getResources(), batch, ingredient),
        unit = unitLabel(batch.getUnit(), name);
    LinearLayout tile = column();
    tile.setGravity(Gravity.CENTER);
    tile.setPadding(dp(2), 0, dp(2), 0);
    tile.setBackground(foodBackground(group, 12));
    TextView emoji = text(InventoryPresentation.emoji(name), compact() ? 22 : 30);
    if (!tiny()) tile.addView(emoji);
    TextView label = text(name, 12);
    label.setMaxLines(1);
    label.setEllipsize(android.text.TextUtils.TruncateAt.END);
    tile.addView(label);
    TextView qty = text(groupQuantity(group, unit), 13);
    qty.setTypeface(null, Typeface.BOLD);
    tile.addView(qty);
    String status =
        model.today() == null
            ? getString(R.string.ac_l10n_inventory_household_date_loading)
            : InventoryPresentation.expiry(getResources(), batch, model.today());
    if (group.batches.size() > 1)
      status =
          getString(R.string.ac_l10n_inventory_batches, group.batches.size())
              + " · "
              + (model.today() != null && InventoryPresentation.expired(batch, model.today())
                  ? getString(R.string.ac_l10n_inventory_includes_expired)
                  : status);
    TextView expiry = text(status, 11);
    expiry.setTextColor(color(expiryColor(batch)));
    expiry.setGravity(Gravity.CENTER);
    if (!compact()) tile.addView(expiry);
    else label.setTextColor(color(expiryColor(batch)));
    for (int i = 0; i < tile.getChildCount(); i++) {
      TextView child = (TextView) tile.getChildAt(i);
      child.setGravity(Gravity.CENTER);
      child.setPadding(0, 0, 0, 0);
    }
    tile.setContentDescription(
        getString(
            R.string.ac_l10n_inventory_food_description,
            name,
            groupQuantity(group, unit).replace(" ", ""),
            status));
    tile.setClickable(true);
    tile.setFocusable(true);
    tile.setOnClickListener(v -> showIngredient(group));
    return tile;
  }

  private View frozenTile(InventoryGroups.Group group) {
    InventoryBatch batch = group.first();
    String name = InventoryPresentation.name(getResources(), batch, model.ingredient(batch));
    LinearLayout row = new LinearLayout(requireContext());
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(3), 0, dp(3), 0);
    row.setBackground(foodBackground(group, 10));
    TextView emoji = text(InventoryPresentation.emoji(name), compact() ? 20 : 26);
    row.addView(emoji, new LinearLayout.LayoutParams(dp(compact() ? 28 : 38), -2));
    LinearLayout labels = column();
    TextView title = text(name, 12);
    title.setMaxLines(1);
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    labels.addView(title);
    TextView qty = text(groupQuantity(group, unitLabel(batch.getUnit(), name)), 13);
    if (model.today() != null && InventoryPresentation.expired(batch, model.today())) {
      qty.append(getString(R.string.ac_l10n_inventory_includes_expired_suffix));
      qty.setTextColor(color(R.color.ac_fridge_expired));
    }
    labels.addView(qty);
    title.setPadding(0, 0, 0, 0);
    qty.setPadding(0, 0, 0, 0);
    row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    row.setContentDescription(
        getString(
            R.string.ac_l10n_inventory_frozen_description,
            name,
            groupQuantity(group, unitLabel(batch.getUnit(), name)).replace(" ", ""),
            group.batches.size()));
    row.setClickable(true);
    row.setFocusable(true);
    row.setOnClickListener(v -> showIngredient(group));
    return row;
  }

  private void buildDetail() {
    fillDetail(binding.acDetail);
  }

  private void fillDetail(LinearLayout detail) {
    detail.removeAllViews();
    TextView kicker = text(getString(R.string.ac_l10n_inventory_kicker), 12);
    kicker.setTextColor(color(R.color.ac_fridge_muted));
    kicker.setLetterSpacing(.12f);
    detail.addView(kicker);
    View rule = new View(requireContext());
    rule.setBackgroundColor(color(R.color.ac_fridge_line));
    LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(-1, dp(1));
    ruleParams.topMargin = dp(12);
    ruleParams.bottomMargin = dp(16);
    detail.addView(rule, ruleParams);
    if (!model.hasHousehold()) {
      detail.addView(text(getString(R.string.ac_l10n_inventory_need_household), 14));
      MaterialButton home = button(getString(R.string.ac_l10n_go_household), false);
      home.setOnClickListener(
          v -> ((InventoryHost) requireActivity()).navigate(Route.HOUSEHOLD, null));
      detail.addView(home);
      return;
    }
    InventoryBatch batch = model.selected();
    if (batch == null) {
      TextView mark = text("✳", 48);
      mark.setTextColor(color(R.color.ac_fridge_accent));
      detail.addView(mark);
      detail.addView(text(getString(R.string.ac_l10n_inventory_motto), 21));
      TextView prompt = text(getString(R.string.ac_l10n_inventory_prompt), 13);
      prompt.setTextColor(color(R.color.ac_fridge_muted));
      prompt.setLineSpacing(dp(5), 1);
      detail.addView(prompt);
      detail.addView(text(getString(R.string.ac_l10n_inventory_zone_legend), 12));
      detail.addView(text(getString(R.string.ac_l10n_inventory_status_legend), 11));
      return;
    }
    String name = InventoryPresentation.name(getResources(), batch, model.ingredient(batch)),
        unit = unitLabel(batch.getUnit(), name);
    detail.addView(text(InventoryPresentation.emoji(name), compact() ? 36 : 62));
    detail.addView(text(name, 22));
    for (var group : foodGroups)
      if (group.contains(batch.getId().toString()) && group.batches.size() > 1) {
        detail.addView(
            text(
                getString(
                        "ALL".equals(model.filter)
                            ? R.string.ac_l10n_inventory_total_stock
                            : R.string.ac_l10n_inventory_filtered_total,
                        groupQuantity(group, unit),
                        group.batches.size()),
                15));
        String usable = group.usableQuantity(model.today());
        detail.addView(
            text(
                getString(
                    R.string.ac_l10n_inventory_available,
                    usable == null
                        ? getString(R.string.ac_l10n_unknown)
                        : usable + " " + unit),
                12));
        MaterialButton batches =
            button(
                getString(R.string.ac_l10n_inventory_view_batches, group.batches.size()), true);
        batches.setOnClickListener(v -> showIngredient(group));
        detail.addView(batches);
        break;
      }
    detail.addView(text(getString(R.string.ac_l10n_inventory_current_batch), 12));
    detail.addView(
        text(getString(R.string.ac_l10n_inventory_remaining, batch.getQuantity(), unit), 29));
    property(detail, getString(R.string.ac_l10n_inventory_location_label), locationLabel(batch.getLocation()));
    property(detail, getString(R.string.ac_l10n_inventory_added_at), displayTime(batch.getCreatedAt()));
    property(
        detail,
        getString(R.string.ac_l10n_inventory_expiry_date),
        batch.getExpiresOn() == null ? getString(R.string.ac_l10n_unknown) : batch.getExpiresOn());
    property(
        detail,
        getString(R.string.ac_l10n_inventory_batch_id),
        batch.getId().toString().substring(0, 8));
    TextView expiry =
        text(
            model.today() == null
                ? getString(R.string.ac_l10n_inventory_date_unknown_consume)
                : InventoryPresentation.expiry(getResources(), batch, model.today()),
            12);
    expiry.setTextColor(color(expiryColor(batch)));
    expiry.setPadding(dp(12), dp(12), dp(12), dp(12));
    expiry.setBackground(round(expiryBackground(batch), 9));
    LinearLayout.LayoutParams expiryParams = new LinearLayout.LayoutParams(-1, -2);
    expiryParams.topMargin = dp(16);
    expiryParams.bottomMargin = dp(14);
    detail.addView(expiry, expiryParams);
    boolean editable = !model.busy && !model.loading && model.pending == null && !model.recovering;
    MaterialButton consume =
        button(
            getString(
                new BigDecimal(batch.getQuantity()).signum() == 0
                    ? R.string.ac_l10n_inventory_used_up
                    : model.today() != null
                            && InventoryPresentation.expired(batch, model.today())
                        ? R.string.ac_l10n_inventory_expired_check
                        : R.string.ac_l10n_inventory_record_consume),
            false);
    consume.setEnabled(
        editable
            && new BigDecimal(batch.getQuantity()).signum() > 0
            && model.today() != null
            && !InventoryPresentation.expired(batch, model.today()));
    consume.setOnClickListener(v -> quantityDialog(batch, ChangeReason.CONSUME));
    detail.addView(consume);
    MaterialButton discard =
        button(getString(R.string.ac_l10n_inventory_record_discard), true);
    discard.setEnabled(editable && new BigDecimal(batch.getQuantity()).signum() > 0);
    discard.setOnClickListener(v -> quantityDialog(batch, ChangeReason.DISCARD));
    detail.addView(discard);
    MaterialButton correction =
        button(getString(R.string.ac_l10n_inventory_correct_stock), true);
    correction.setEnabled(editable);
    correction.setOnClickListener(v -> quantityDialog(batch, ChangeReason.CORRECT));
    detail.addView(correction);
    MaterialButton edit = button(getString(R.string.ac_l10n_inventory_edit_batch), true);
    edit.setEnabled(editable);
    edit.setOnClickListener(v -> editDialog(batch));
    detail.addView(edit);
    if (model.latestMovement != null) {
      InventoryMovement movement = model.latestMovement;
      String kind =
          getString(
              switch (movement.getReason()) {
                case CONSUME -> R.string.ac_l10n_inventory_movement_consume;
                case DISCARD -> R.string.ac_l10n_inventory_movement_discard;
                case CORRECT -> R.string.ac_l10n_inventory_movement_correct;
                case COOK -> R.string.ac_l10n_inventory_movement_cook;
              });
      TextView recent =
          text(
              getString(
                  R.string.ac_l10n_inventory_movement_row,
                  kind,
                  movement.getBeforeQuantity(),
                  movement.getAfterQuantity(),
                  unit,
                  displayDate(movement.getCreatedAt())),
              12);
      recent.setId(R.id.ac_recent_movement);
      recent.setTextColor(color(R.color.ac_fridge_muted));
      detail.addView(recent);
    } else if (model.movementMessage != null) {
      detail.addView(text(localized(model.movementMessage), 12));
    }
  }

  private boolean compact() {
    return workspaceHeight > 0
        && workspaceHeight / getResources().getDisplayMetrics().density < 800;
  }

  private boolean tiny() {
    return workspaceHeight > 0
        && workspaceHeight / getResources().getDisplayMetrics().density < 500;
  }

  private void selectBatch(InventoryBatch batch) {
    model.select(batch);
    if (getResources().getConfiguration().screenWidthDp < 720) showDetail();
  }

  private void showDetail() {
    if (detailDialog != null || binding == null) return;
    presentedId = model.selectedId;
    sheetDetail = column();
    sheetDetail.setPadding(dp(20), dp(8), dp(20), dp(16));
    fillDetail(sheetDetail);
    ScrollView scroll = new ScrollView(requireContext());
    scroll.addView(sheetDetail);
    detailDialog = new BottomSheetDialog(requireContext());
    detailDialog.setContentView(scroll);
    detailDialog.setOnDismissListener(
        d -> {
          detailDialog = null;
          sheetDetail = null;
        });
    detailDialog.show();
    detailDialog
        .getBehavior()
        .setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
  }

  private void showBatches(String title, List<InventoryBatch> batches) {
    if (batches.isEmpty()) {
      new MaterialAlertDialogBuilder(requireContext())
          .setTitle(title)
          .setMessage(R.string.ac_l10n_inventory_shelf_empty)
          .setPositiveButton(R.string.ac_l10n_ack, null)
          .show();
      return;
    }
    String[] choices = new String[batches.size()];
    for (int i = 0; i < batches.size(); i++) {
      InventoryBatch batch = batches.get(i);
      String name = InventoryPresentation.name(getResources(), batch, model.ingredient(batch));
      choices[i] =
          getString(
              R.string.ac_l10n_inventory_batch_row,
              name,
              batch.getQuantity(),
              unitLabel(batch.getUnit(), name),
              displayTime(batch.getCreatedAt()),
              batch.getExpiresOn() == null
                  ? getString(R.string.ac_l10n_unknown)
                  : batch.getExpiresOn(),
              locationLabel(batch.getLocation()),
              batch.getId().toString().substring(0, 8));
    }
    new MaterialAlertDialogBuilder(requireContext())
        .setTitle(title)
        .setItems(choices, (d, which) -> selectBatch(batches.get(which)))
        .setNegativeButton(R.string.ac_l10n_inventory_close, null)
        .show();
  }

  private String groupQuantity(InventoryGroups.Group group, String unit) {
    return group.quantity() == null
        ? getString(R.string.ac_l10n_inventory_quantity_unknown)
        : group.quantity() + " " + unit;
  }

  private void showIngredient(InventoryGroups.Group group) {
    if (group.batches.size() == 1) {
      selectBatch(group.first());
      return;
    }
    String name =
        InventoryPresentation.name(
            getResources(), group.first(), model.ingredient(group.first()));
    String unit = unitLabel(group.first().getUnit(), name);
    showBatches(
        name
            + " · "
            + getString(
                "ALL".equals(model.filter)
                    ? R.string.ac_l10n_inventory_total_prefix
                    : R.string.ac_l10n_inventory_filtered_prefix,
                groupQuantity(group, unit))
            + " · "
            + getString(R.string.ac_l10n_inventory_batches, group.batches.size()),
        group.batches);
  }

  private void requestAddFood() {
    String reason =
        !model.hasHousehold()
            ? getString(R.string.ac_l10n_inventory_reason_no_household)
            : model.loading
                ? getString(R.string.ac_l10n_inventory_reason_loading)
                : model.recovering
                    ? getString(R.string.ac_l10n_inventory_reason_recovering)
                    : model.pending != null
                        ? getString(R.string.ac_l10n_inventory_reason_pending)
                        : null;
    if (reason != null) {
      var notice =
          new MaterialAlertDialogBuilder(requireContext())
              .setTitle(
                  !model.hasHousehold()
                      ? R.string.ac_l10n_inventory_set_household_title
                      : R.string.ac_l10n_please_wait)
              .setMessage(reason)
              .setNegativeButton(android.R.string.cancel, null);
      if (!model.hasHousehold())
        notice.setPositiveButton(
            R.string.ac_l10n_go_household,
            (d, w) -> ((InventoryHost) requireActivity()).navigate(Route.HOUSEHOLD, null));
      else notice.setPositiveButton(R.string.ac_l10n_ack, null);
      notice.show();
      return;
    }
    addFood();
  }

  private void property(LinearLayout detail, String label, String value) {
    LinearLayout row = new LinearLayout(requireContext());
    row.setGravity(Gravity.CENTER_VERTICAL);
    TextView key = text(label, 12);
    key.setTextColor(color(R.color.ac_fridge_muted));
    row.addView(key, new LinearLayout.LayoutParams(dp(74), -2));
    TextView content = text(value == null ? getString(R.string.ac_l10n_unknown) : value, 12);
    content.setGravity(Gravity.END);
    row.addView(content, new LinearLayout.LayoutParams(0, -2, 1));
    detail.addView(row);
  }

  private GradientDrawable foodBackground(InventoryGroups.Group group, int radius) {
    GradientDrawable shape = round(R.color.ac_fridge_selected, radius);
    if (group.contains(model.selectedId)) {
      shape.setStroke(dp(1), color(R.color.ac_fridge_accent));
    } else {
      shape.setColor(android.graphics.Color.TRANSPARENT);
    }
    return shape;
  }

  private String displayDate(String timestamp) {
    if (timestamp == null) return getString(R.string.ac_l10n_unknown);
    try {
      return java.time.OffsetDateTime.parse(timestamp)
          .atZoneSameInstant(java.time.ZoneId.of(model.zoneId))
          .toLocalDate()
          .toString();
    } catch (java.time.DateTimeException | NullPointerException invalid) {
      return timestamp;
    }
  }

  private String displayTime(String timestamp) {
    if (timestamp == null) return getString(R.string.ac_l10n_unknown);
    try {
      return java.time.OffsetDateTime.parse(timestamp)
          .atZoneSameInstant(java.time.ZoneId.of(model.zoneId))
          .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    } catch (java.time.DateTimeException | NullPointerException invalid) {
      return timestamp;
    }
  }

  private int expiryColor(InventoryBatch batch) {
    if (model.today() == null || batch.getExpiresOn() == null) return R.color.ac_fridge_muted;
    if (InventoryPresentation.expired(batch, model.today())) return R.color.ac_fridge_expired;
    if (java.time.LocalDate.parse(batch.getExpiresOn()).isAfter(model.today().plusDays(3)))
      return R.color.ac_fridge_muted;
    return R.color.ac_fridge_soon;
  }

  private int expiryBackground(InventoryBatch batch) {
    int color = expiryColor(batch);
    return color == R.color.ac_fridge_expired
        ? R.color.ac_fridge_expired_background
        : color == R.color.ac_fridge_soon
            ? R.color.ac_fridge_soon_background
            : R.color.ac_fridge_selected;
  }

  private void addFood() {
    Set<UUID> usedAtHome = new HashSet<>();
    for (InventoryBatch batch : model.batches) usedAtHome.add(batch.getIngredientId());
    IngredientChoices choices = new IngredientChoices(model.ingredients.values(), usedAtHome);
    LinearLayout form = column();
    form.setPadding(dp(20), dp(8), dp(20), dp(8));
    TextInputLayout nameLayout = new TextInputLayout(requireContext());
    nameLayout.setHint(getString(R.string.ac_l10n_inventory_ingredient_hint));
    TextInputEditText picker = new TextInputEditText(nameLayout.getContext());
    picker.setId(R.id.ac_food_name);
    picker.setSingleLine(true);
    picker.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
    picker.setText(model.draftName);
    nameLayout.addView(picker, new LinearLayout.LayoutParams(-1, -2));
    form.addView(nameLayout, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout matches = column();
    form.addView(matches);
    final Ingredient[] selected = {choices.exact(model.draftName)};
    final boolean[] choosing = {false};
    LinearLayout quantityRow = new LinearLayout(requireContext());
    quantityRow.setGravity(Gravity.CENTER_VERTICAL);
    quantityRow.setOrientation(LinearLayout.HORIZONTAL);
    TextInputEditText quantity =
        input(
            quantityRow,
            getString(R.string.ac_l10n_inventory_remaining_quantity),
            model.draftQuantity,
            true);
    quantityRow.getChildAt(0).setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
    ChipGroup units = new ChipGroup(requireContext());
    units.setId(R.id.ac_unit_input);
    units.setSingleLine(true);
    units.setSingleSelection(true);
    units.setSelectionRequired(true);
    units.setChipSpacingHorizontal(0);
    int[] unitChoices = {1, 4, 2, 3};
    int[] unitIds = {R.id.ac_unit_g, R.id.ac_unit_kg, R.id.ac_unit_count, R.id.ac_unit_ml};
    String[] unitLabels = {"g", "kg", "pcs", "ml"};
    for (int i = 0; i < unitChoices.length; i++) {
      Chip chip = new Chip(requireContext());
      chip.setId(unitIds[i]);
      chip.setTag(unitChoices[i]);
      chip.setText(unitLabels[i]);
      chip.setTextSize(12);
      chip.setCheckable(true);
      chip.setCheckedIconVisible(false);
      chip.setChipMinHeight(dp(40));
      chip.setEnsureMinTouchTargetSize(true);
      chip.setChipStartPadding(dp(5));
      chip.setChipEndPadding(dp(5));
      chip.setTextStartPadding(dp(2));
      chip.setTextEndPadding(dp(2));
      units.addView(chip);
    }
    units.setOnCheckedStateChangeListener(
        (group, ids) -> {
          clearFormValidation(form);
          model.draftUnitIndex = ids.isEmpty() ? 0 : (int) group.findViewById(ids.get(0)).getTag();
          Unit base = InventoryPresentation.inputBaseUnit(model.draftUnitIndex);
          units.setContentDescription(
              base == null
                  ? getString(R.string.ac_l10n_inventory_unit_not_chosen)
                  : getString(
                      R.string.ac_l10n_inventory_unit_prefix,
                      model.draftUnitIndex == 4
                          ? getString(R.string.ac_l10n_inventory_kilogram)
                          : unitLabel(base, picker.getText().toString())));
        });
    quantityRow.addView(units, new LinearLayout.LayoutParams(-2, -2));
    form.addView(quantityRow, new LinearLayout.LayoutParams(-1, -2));
    TextInputEditText expiry =
        input(
            form,
            getString(R.string.ac_l10n_inventory_expiry_hint),
            model.draftExpiry,
            false);
    quantity.setId(R.id.ac_quantity_input);
    expiry.setId(R.id.ac_expiry_input);
    Spinner location = location(form, model.draftLocation);
    location.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          @Override
          public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
            model.draftLocation = pos;
            clearFormValidation(form);
          }

          @Override
          public void onNothingSelected(AdapterView<?> p) {}
        });
    Runnable updateUnit =
        () -> {
          Unit standard = selected[0] == null ? null : selected[0].getUnit();
          if (standard != null
              && InventoryPresentation.inputBaseUnit(model.draftUnitIndex) != standard)
            model.draftUnitIndex = standard == Unit.G ? 1 : standard == Unit.COUNT ? 2 : 3;
          String typed = picker.getText().toString().toLowerCase(Locale.ROOT);
          boolean liquid =
              standard == Unit.ML
                  || model.draftUnitIndex == 3
                  || typed.matches(".*\\b(water|juice|oil|vinegar|sauce)\\b.*");
          int selectedChoice = model.draftUnitIndex;
          for (int i = 0; i < unitChoices.length; i++) {
            Chip chip = units.findViewById(unitIds[i]);
            Unit base = InventoryPresentation.inputBaseUnit(unitChoices[i]);
            chip.setEnabled(standard == null || standard == base);
            chip.setVisibility(unitChoices[i] == 3 && !liquid ? View.GONE : View.VISIBLE);
          }
          if (selectedChoice == 0) units.clearCheck();
          else
            for (int i = 0; i < unitChoices.length; i++)
              if (unitChoices[i] == selectedChoice) units.check(unitIds[i]);
          units.setContentDescription(
              standard == null && selectedChoice == 0
                  ? getString(R.string.ac_l10n_inventory_unit_not_chosen)
                  : getString(
                      R.string.ac_l10n_inventory_unit_prefix,
                      selectedChoice == 4
                          ? getString(R.string.ac_l10n_inventory_kilogram)
                          : unitLabel(
                              InventoryPresentation.inputBaseUnit(selectedChoice),
                              picker.getText().toString())));
        };
    java.util.function.Consumer<Ingredient> choose =
        item -> {
          selected[0] = item;
          choosing[0] = true;
          picker.setText(item.getName());
          picker.setSelection(picker.length());
          choosing[0] = false;
          model.draftName = item.getName();
          location.setSelection(InventoryPresentation.suggestedShelf(item.getName()));
          updateUnit.run();
          matches.removeAllViews();
          quantity.requestFocus();
        };
    Runnable showMatches =
        () -> {
          matches.removeAllViews();
          String query = picker.getText().toString();
          if (IngredientChoices.normalize(query)
                  .codePointCount(0, IngredientChoices.normalize(query).length())
              < 2) return;
          if (choices.exact(query) != null) return;
          List<Ingredient> results = choices.search(query);
          if (results.isEmpty()) {
            matches.addView(
                plainText(getString(R.string.ac_l10n_inventory_new_ingredient), 12));
            return;
          }
          for (Ingredient item : results.subList(0, Math.min(3, results.size()))) {
            MaterialButton candidate = button(IngredientChoices.label(item), true);
            candidate.setLetterSpacing(0);
            candidate.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            candidate.setMaxLines(1);
            candidate.setEllipsize(android.text.TextUtils.TruncateAt.END);
            candidate.setOnClickListener(v -> choose.accept(item));
            matches.addView(candidate, new LinearLayout.LayoutParams(-1, dp(44)));
          }
        };
    watch(
        picker,
        value -> {
          boolean different = !Objects.equals(model.draftName, value);
          int oldSuggestion = InventoryPresentation.suggestedShelf(model.draftName);
          boolean semanticChange =
              !IngredientChoices.normalize(model.draftName)
                  .equals(IngredientChoices.normalize(value));
          Ingredient previous = selected[0];
          model.draftName = value;
          if (choosing[0]) return;
          Ingredient exact = choices.exact(value);
          if (semanticChange && !value.trim().isEmpty()) {
            int suggestion =
                InventoryPresentation.suggestedShelf(exact == null ? value : exact.getName());
            if (location.getSelectedItemPosition() == oldSuggestion || suggestion != oldSuggestion)
              location.setSelection(suggestion);
          }
          selected[0] = exact;
          if (different && exact == null && previous != null) model.draftUnitIndex = 0;
          updateUnit.run();
          showMatches.run();
        });
    updateUnit.run();
    showMatches.run();
    watch(quantity, value -> model.draftQuantity = value);
    watch(expiry, value -> model.draftExpiry = value);
    form.addView(
        plainText(getString(R.string.ac_l10n_inventory_batch_note), 12));
    showForm(
        getString(R.string.ac_l10n_inventory_add_title),
        form,
        () -> {
          String typed = picker.getText().toString();
          if (typed.trim().isEmpty())
            throw new AcValidationException(
                AcMessage.of(R.string.ac_l10n_inventory_enter_name));
          Ingredient ingredient = selected[0];
          if (ingredient == null
              || !IngredientChoices.normalize(typed)
                  .equals(IngredientChoices.normalize(ingredient.getName())))
            ingredient = choices.exact(typed);
          if (ingredient == null) {
            Unit chosenUnit = InventoryPresentation.inputBaseUnit(model.draftUnitIndex);
            String amount =
                InventoryPresentation.inputQuantity(
                    quantity.getText().toString(), model.draftUnitIndex);
            Ingredient existing = chosenUnit == null ? null : choices.exact(typed, chosenUnit);
            if (existing != null)
              model.create(
                  existing,
                  amount,
                  InventoryPresentation.LOCATIONS[location.getSelectedItemPosition()],
                  expiry.getText().toString());
            else
              model.createNamed(
                  typed,
                  chosenUnit,
                  amount,
                  InventoryPresentation.LOCATIONS[location.getSelectedItemPosition()],
                  expiry.getText().toString());
          } else {
            if (ingredient.getUnit() != InventoryPresentation.inputBaseUnit(model.draftUnitIndex))
              throw new AcValidationException(
                  AcMessage.of(R.string.ac_l10n_inventory_unit_mismatch));
            model.create(
                ingredient,
                InventoryPresentation.inputQuantity(
                    quantity.getText().toString(), model.draftUnitIndex),
                InventoryPresentation.LOCATIONS[location.getSelectedItemPosition()],
                expiry.getText().toString());
          }
          model.draftName = "";
          model.draftUnitIndex = 0;
          model.draftQuantity = "";
          model.draftExpiry = "";
        });
  }

  private void quantityDialog(InventoryBatch batch, ChangeReason reason) {
    String unit =
        unitLabel(
            batch.getUnit(),
            InventoryPresentation.name(getResources(), batch, model.ingredient(batch)));
    boolean correction = reason == ChangeReason.CORRECT;
    String title =
        getString(
            correction
                ? R.string.ac_l10n_inventory_correct_stock
                : reason == ChangeReason.DISCARD
                    ? R.string.ac_l10n_inventory_record_discard
                    : R.string.ac_l10n_inventory_record_consume);
    LinearLayout form = column();
    form.setPadding(dp(20), dp(8), dp(20), dp(8));
    form.addView(
        plainText(
            getString(
                R.string.ac_l10n_inventory_current_remaining, batch.getQuantity(), unit),
            14));
    TextInputEditText amount =
        input(
            form,
            correction
                ? getString(R.string.ac_l10n_inventory_corrected_hint, unit)
                : getString(R.string.ac_l10n_inventory_used_hint, unit),
            "",
            true);
    amount.setId(R.id.ac_quantity_input);
    TextView preview = plainText(getString(R.string.ac_l10n_inventory_balance_hint), 14);
    form.addView(preview);
    watch(
        amount,
        value -> {
          try {
            String after =
                correction
                    ? InventoryPresentation.number(InventoryPresentation.quantity(value))
                    : InventoryPresentation.after(batch.getQuantity(), value);
            preview.setText(
                getString(R.string.ac_l10n_inventory_after_remaining, after, unit));
            amount.setError(null);
          } catch (IllegalArgumentException error) {
            preview.setText(R.string.ac_l10n_inventory_invalid_amount);
          }
        });
    if (!correction) {
      MaterialButton all = button(getString(R.string.ac_l10n_inventory_use_all), true);
      all.setOnClickListener(v -> amount.setText(batch.getQuantity()));
      form.addView(all);
    }
    final androidx.appcompat.app.AlertDialog[] entry = {null};
    entry[0] =
        showValidatedForm(
            title,
            form,
            () -> {
              String value = amount.getText().toString();
              String after =
                  correction
                      ? InventoryPresentation.number(InventoryPresentation.quantity(value))
                      : InventoryPresentation.after(batch.getQuantity(), value);
              if (reason != ChangeReason.CONSUME || !"0".equals(after)) {
                model.change(batch, value, reason);
                return true;
              }
              String name =
                  InventoryPresentation.name(getResources(), batch, model.ingredient(batch));
              boolean last =
                  "ALL".equals(model.filter)
                      && !InventoryPresentation.otherBatchesRemain(batch, model.batches);
              String message =
                  getString(
                      last
                          ? R.string.ac_l10n_inventory_used_up_all
                          : R.string.ac_l10n_inventory_used_up_batch,
                      name);
              var confirm =
                  new MaterialAlertDialogBuilder(requireContext())
                      .setTitle(R.string.ac_l10n_inventory_confirm_used_up_title)
                      .setMessage(message)
                      .setNegativeButton(
                          R.string.ac_l10n_inventory_back_to_edit,
                          (d, which) -> {
                            amount.requestFocus();
                            amount.selectAll();
                          })
                      .setPositiveButton(
                          R.string.ac_l10n_inventory_confirm_used_up,
                          (d, which) -> {
                            try {
                              model.change(batch, value, reason);
                              entry[0].dismiss();
                            } catch (IllegalArgumentException error) {
                              amount.setError(errorMessage(error));
                            }
                          })
                      .create();
              dialogs.add(confirm);
              confirm.setOnDismissListener(d -> dialogs.remove(confirm));
              confirm.show();
              return false;
            });
  }

  private void editDialog(InventoryBatch batch) {
    LinearLayout form = column();
    form.setPadding(dp(20), dp(8), dp(20), dp(8));
    int index = -1;
    for (int i = 0; i < InventoryPresentation.LOCATIONS.length; i++)
      if (InventoryPresentation.LOCATIONS[i].equals(batch.getLocation())) index = i;
    Spinner location = location(form, index < 0 ? 1 : index);
    if (index < 0) {
      String[] options = Arrays.copyOf(locationLabels(), 6);
      options[5] = batch.getLocation();
      location.setAdapter(
          new ArrayAdapter<>(
              requireContext(), android.R.layout.simple_spinner_dropdown_item, options));
      location.setSelection(5);
    }
    TextInputEditText expiry =
        input(
            form,
            getString(R.string.ac_l10n_inventory_expiry_hint),
            batch.getExpiresOn() == null ? "" : batch.getExpiresOn(),
            false);
    expiry.setId(R.id.ac_expiry_input);
    showForm(
        getString(R.string.ac_l10n_inventory_edit_batch_title),
        form,
        () ->
            model.update(
                batch,
                location.getSelectedItemPosition() == 5
                    ? batch.getLocation()
                    : InventoryPresentation.LOCATIONS[location.getSelectedItemPosition()],
                expiry.getText().toString()));
  }

  private void showForm(String title, LinearLayout form, Runnable submit) {
    showValidatedForm(
        title,
        form,
        () -> {
          submit.run();
          return true;
        });
  }

  private androidx.appcompat.app.AlertDialog showValidatedForm(
      String title, LinearLayout form, java.util.function.BooleanSupplier submit) {
    TextView validation = plainText("", 13);
    validation.setId(R.id.ac_form_error);
    validation.setTextColor(color(R.color.ac_fridge_expired));
    validation.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    validation.setVisibility(View.GONE);
    form.addView(validation);
    clearValidationOnEdit(form, form);
    ScrollView scroll = new ScrollView(requireContext());
    scroll.addView(form);
    var dialog =
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.ac_l10n_confirm_save, null)
            .create();
    dialogs.add(dialog);
    dialog.setOnShowListener(
        ignored ->
            dialog
                .getButton(-1)
                .setOnClickListener(
                    v -> {
                      try {
                        if (submit.getAsBoolean()) dialog.dismiss();
                      } catch (IllegalArgumentException error) {
                        validation.setText(errorMessage(error));
                        validation.setVisibility(View.VISIBLE);
                      }
                    }));
    dialog.setOnDismissListener(ignored -> dialogs.remove(dialog));
    dialog.show();
    return dialog;
  }

  private void clearFormValidation(View form) {
    View error = form.findViewById(R.id.ac_form_error);
    if (error != null) error.setVisibility(View.GONE);
  }

  private void clearValidationOnEdit(View view, View form) {
    if (view instanceof EditText edit) {
      watch(
          edit,
          ignored -> {
            edit.setError(null);
            clearFormValidation(form);
          });
    } else if (view instanceof ViewGroup group) {
      for (int i = 0; i < group.getChildCount(); i++)
        clearValidationOnEdit(group.getChildAt(i), form);
    }
  }

  private Spinner location(LinearLayout form, int selected) {
    form.addView(plainText(getString(R.string.ac_l10n_inventory_location_label), 13));
    Spinner spinner = new Spinner(requireContext());
    spinner.setId(R.id.ac_location_input);
    spinner.setMinimumHeight(dp(48));
    spinner.setAdapter(
        new ArrayAdapter<>(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            locationLabels()));
    spinner.setSelection(Math.max(0, selected));
    form.addView(spinner);
    return spinner;
  }

  /** Display labels for the stored location values; the values themselves never change. */
  private String[] locationLabels() {
    String[] labels = new String[InventoryPresentation.LOCATIONS.length];
    for (int i = 0; i < labels.length; i++)
      labels[i] = locationLabel(InventoryPresentation.LOCATIONS[i]);
    return labels;
  }

  private String locationLabel(String stored) {
    for (int i = 0; i < InventoryPresentation.LOCATIONS.length; i++)
      if (InventoryPresentation.LOCATIONS[i].equals(stored))
        return getString(LOCATION_LABELS[i]);
    return stored;
  }

  private static final int[] LOCATION_LABELS = {
    R.string.ac_l10n_location_chill_dairy,
    R.string.ac_l10n_location_chill_veg,
    R.string.ac_l10n_location_chill_fruit,
    R.string.ac_l10n_location_freeze_meat,
    R.string.ac_l10n_location_freeze_seafood
  };

  private String unitLabel(Unit unit, String name) {
    return AcText.unit(getResources(), unit, name);
  }

  private String localized(AcMessage message) {
    return message == null ? "" : message.format(getResources());
  }

  /** Resolves a validation error: resource-backed errors are localized, other errors pass through. */
  private String errorMessage(IllegalArgumentException error) {
    if (error instanceof AcValidationException localized)
      return localized.format(getResources());
    return error.getMessage();
  }

  private TextInputEditText input(LinearLayout form, String label, String value, boolean numeric) {
    TextInputLayout layout = new TextInputLayout(requireContext());
    layout.setHint(label);
    TextInputEditText edit = new TextInputEditText(layout.getContext());
    edit.setSingleLine(true);
    edit.setInputType(
        numeric
            ? android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            : android.text.InputType.TYPE_CLASS_TEXT);
    edit.setText(value);
    layout.addView(edit, new LinearLayout.LayoutParams(-1, -2));
    form.addView(layout, new LinearLayout.LayoutParams(-1, -2));
    return edit;
  }

  private void watch(EditText edit, java.util.function.Consumer<String> change) {
    edit.addTextChangedListener(
        new TextWatcher() {
          @Override
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          @Override
          public void onTextChanged(CharSequence s, int start, int before, int count) {
            change.accept(s.toString());
          }

          @Override
          public void afterTextChanged(Editable e) {}
        });
  }

  private LinearLayout column() {
    LinearLayout column = new LinearLayout(requireContext());
    column.setOrientation(LinearLayout.VERTICAL);
    return column;
  }

  private TextView text(String value, int size) {
    TextView text = plainText(value, size);
    text.setTextColor(color(R.color.ac_fridge_ink));
    return text;
  }

  private TextView plainText(String value, int size) {
    TextView text = new TextView(requireContext());
    text.setText(value);
    text.setTextSize(size);
    text.setPadding(0, dp(5), 0, dp(5));
    return text;
  }

  private MaterialButton button(String label, boolean outline) {
    MaterialButton button =
        new MaterialButton(
            requireContext(),
            null,
            outline
                ? com.google.android.material.R.attr.materialButtonOutlinedStyle
                : com.google.android.material.R.attr.materialButtonStyle);
    button.setText(label);
    button.setAllCaps(false);
    button.setMinimumHeight(dp(48));
    button.setTextSize(12);
    button.setCornerRadius(dp(28));
    button.setElevation(0);
    button.setStateListAnimator(null);
    button.setStrokeWidth(outline ? dp(1) : 0);
    button.setStrokeColor(ColorStateList.valueOf(color(R.color.ac_fridge_line)));
    button.setBackgroundTintList(
        new ColorStateList(
            new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
            new int[] {
              color(R.color.ac_fridge_line),
              color(outline ? R.color.ac_fridge_panel : R.color.ac_fridge_ink)
            }));
    button.setTextColor(
        new ColorStateList(
            new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
            new int[] {
              color(R.color.ac_fridge_muted),
              color(outline ? R.color.ac_fridge_ink : R.color.ac_fridge_background)
            }));
    return button;
  }

  private GradientDrawable gradient(int start, int center, int end, int radius) {
    GradientDrawable shape =
        new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            new int[] {color(start), color(center), color(end)});
    shape.setCornerRadius(dp(radius));
    shape.setStroke(dp(1), color(R.color.ac_fridge_line));
    return shape;
  }

  private GradientDrawable round(int resource, int radius) {
    GradientDrawable shape = new GradientDrawable();
    shape.setColor(color(resource));
    shape.setCornerRadius(dp(radius));
    return shape;
  }

  private int color(int id) {
    return androidx.core.content.ContextCompat.getColor(requireContext(), id);
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
