package edu.xjtlu.civa.feature.ac.inventory;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import edu.xjtlu.civa.api.model.*;
import edu.xjtlu.civa.core.data.*;
import edu.xjtlu.civa.core.ui.ApiError;
import edu.xjtlu.civa.feature.ac.AcMessage;
import edu.xjtlu.civa.feature.ac.AcValidationException;
import edu.xjtlu.civa.feature.ac.R;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.json.JSONObject;
import retrofit2.Call;

/** Real API state, cancellable reads, household isolation and durable explicit write retry. */
final class InventoryViewModel extends ViewModel {
  final MutableLiveData<Integer> revision = new MutableLiveData<>(0);
  final List<InventoryBatch> batches = new ArrayList<>();
  final Map<UUID, Ingredient> ingredients = new LinkedHashMap<>();
  final List<Call<?>> requests = new ArrayList<>();
  boolean coldOpen, freezeOpen, busy, loading, closed, recovering;
  String filter = "ALL", selectedId, zoneId;
  AcMessage message;
  private AcMessage outcome;
  String draftName = "", draftQuantity = "", draftExpiry = "";
  int draftIngredient, draftUnitIndex, draftLocation = 1;
  private final ApiProvider api;
  private final ApiCalls calls;
  private final SessionStore sessions;
  private String userId, homeId;
  private int readGeneration;
  PendingWrite pending;
  InventoryMovement latestMovement;
  AcMessage movementMessage;

  InventoryViewModel(ApiProvider api, ApiCalls calls, SessionStore sessions) {
    this.api = api;
    this.calls = calls;
    this.sessions = sessions;
  }

  private void changed() {
    revision.setValue(revision.getValue() + 1);
  }

  private boolean current() {
    return !closed
        && Objects.equals(userId, sessions.currentUserId())
        && Objects.equals(homeId, sessions.currentHouseholdId());
  }

  boolean hasHousehold() {
    return sessions.currentHouseholdId() != null;
  }

  boolean scopeChanged() {
    return !Objects.equals(userId, sessions.currentUserId())
        || !Objects.equals(homeId, sessions.currentHouseholdId());
  }

  LocalDate today() {
    return zoneId == null ? null : LocalDate.now(ZoneId.of(zoneId));
  }

  Ingredient ingredient(InventoryBatch batch) {
    return ingredients.get(batch.getIngredientId());
  }

  InventoryBatch selected() {
    for (var b : batches) if (b.getId().toString().equals(selectedId)) return b;
    return null;
  }

  void toggle(boolean cold) {
    if (cold) coldOpen = !coldOpen;
    else freezeOpen = !freezeOpen;
    changed();
  }

  void toggleAll() {
    boolean open = !(coldOpen && freezeOpen);
    coldOpen = open;
    freezeOpen = open;
    changed();
  }

  void select(InventoryBatch batch) {
    selectedId = batch.getId().toString();
    latestMovement = null;
    movementMessage = null;
    changed();
    loadMovement();
  }

  void filter(String value) {
    filter = value;
    load();
  }

  void load() {
    if (closed || busy) return;
    String user = sessions.currentUserId(), home = sessions.currentHouseholdId();
    if (!Objects.equals(user, userId) || !Objects.equals(home, homeId)) {
      outcome = null;
      batches.clear();
      ingredients.clear();
      pending = null;
      selectedId = null;
      zoneId = null;
    }
    userId = user;
    homeId = home;
    cancelReads();
    latestMovement = null;
    movementMessage = null;
    int generation = ++readGeneration;
    if (userId == null || homeId == null) {
      loading = false;
      message = AcMessage.of(R.string.ac_l10n_inventory_need_login);
      changed();
      return;
    }
    loading = true;
    message = AcMessage.of(R.string.ac_l10n_inventory_loading);
    changed();
    read(
        api.household().getHousehold(UUID.fromString(homeId)),
        generation,
        homeValue -> {
          zoneId = homeValue.getZoneId();
          changed();
        });
    loadIngredients(0, generation, new LinkedHashMap<>());
    loadBatches(0, generation, new ArrayList<>());
    recover();
  }

  private void loadIngredients(int page, int generation, Map<UUID, Ingredient> result) {
    read(
        api.inventory().listIngredients(null, page, 100),
        generation,
        value -> {
          for (Ingredient item : value.getItems()) result.put(item.getId(), item);
          if ((long) (page + 1) * 100 < value.getTotal())
            loadIngredients(page + 1, generation, result);
          else {
            ingredients.clear();
            ingredients.putAll(result);
            changed();
          }
        });
  }

  private void loadMovement() {
    InventoryBatch selected = selected();
    if (selected == null || !current()) return;
    String batchId = selected.getId().toString();
    int generation = readGeneration;
    Call<InventoryMovementPage> call =
        api.inventory().listInventoryMovements(UUID.fromString(homeId), selected.getId(), 0, 1);
    requests.add(call);
    calls.enqueue(
        call,
        new ApiCallback<InventoryMovementPage>() {
          @Override
          public void onSuccess(InventoryMovementPage value) {
            requests.remove(call);
            if (!current() || generation != readGeneration || !Objects.equals(batchId, selectedId))
              return;
            if (value == null || value.getItems() == null) {
              movementMessage = AcMessage.of(R.string.ac_l10n_inventory_movement_load_failed);
            } else {
              latestMovement = value.getItems().isEmpty() ? null : value.getItems().get(0);
              movementMessage = null;
            }
            changed();
          }

          @Override
          public void onError(ApiError error) {
            requests.remove(call);
            if (!current() || generation != readGeneration || !Objects.equals(batchId, selectedId))
              return;
            movementMessage = AcMessage.of(R.string.ac_l10n_inventory_movement_not_loaded);
            changed();
          }
        });
  }

  private void loadBatches(int page, int generation, List<InventoryBatch> result) {
    read(
        api.inventory()
            .listInventoryBatches(
                UUID.fromString(homeId), null, ExpiryStatus.fromValue(filter), page, 100),
        generation,
        value -> {
          result.addAll(value.getItems());
          if ((long) (page + 1) * 100 < value.getTotal()) loadBatches(page + 1, generation, result);
          else {
            batches.clear();
            for (InventoryBatch batch : result)
              if (InventoryPresentation.inFridge(batch)) batches.add(batch);
            if (selected() == null) {
              selectedId = null;
              latestMovement = null;
              movementMessage = null;
            }
            loading = false;
            // The routine "loaded N batches" summary is not shown; only an explicit write outcome is.
            message = outcome;
            changed();
            loadMovement();
          }
        });
  }

  private <T> void read(Call<T> call, int generation, java.util.function.Consumer<T> success) {
    requests.add(call);
    calls.enqueue(
        call,
        new ApiCallback<T>() {
          @Override
          public void onSuccess(T value) {
            requests.remove(call);
            if (!current() || generation != readGeneration) return;
            if (value == null) {
              fail("INVALID_RESPONSE");
              return;
            }
            success.accept(value);
          }

          @Override
          public void onError(ApiError error) {
            requests.remove(call);
            if (closed || generation != readGeneration) return;
            if (!current()) {
              clearVisible();
              return;
            }
            fail(error.code);
          }
        });
  }

  private void clearVisible() {
    batches.clear();
    ingredients.clear();
    pending = null;
    latestMovement = null;
    movementMessage = null;
    loading = false;
    busy = false;
    message = AcMessage.of(R.string.ac_l10n_inventory_login_changed);
    changed();
  }

  private void cancelReads() {
    for (Call<?> call : new ArrayList<>(requests)) call.cancel();
    requests.clear();
  }

  private void recover() {
    if (recovering || pending != null) return;
    recovering = true;
    calls
        .pendingWrites()
        .listPending(
            userId,
            new ApiCallback<List<PendingWrite>>() {
              @Override
              public void onSuccess(List<PendingWrite> values) {
                recovering = false;
                if (!current()) return;
                List<PendingWrite> ordered = new ArrayList<>(values);
                ordered.sort(
                    java.util.Comparator.comparingInt(
                        w -> "createInventoryBatch".equals(w.operationId) ? 0 : 1));
                for (var value : ordered)
                  if (Objects.equals(homeId, value.householdId)
                      && ("createInventoryBatch".equals(value.operationId)
                          || "changeInventory".equals(value.operationId)
                          || "createIngredient".equals(value.operationId))) {
                    pending = value;
                    message = AcMessage.of(R.string.ac_l10n_inventory_pending_found);
                    break;
                  }
                changed();
              }

              @Override
              public void onError(ApiError error) {
                recovering = false;
                if (current()) fail(error.code);
              }
            });
  }

  void create(Ingredient ingredient, String quantity, String location, String expiry) {
    if (ingredient == null)
      throw new AcValidationException(AcMessage.of(R.string.ac_l10n_inventory_choose_ingredient));
    String amount = InventoryPresentation.positiveQuantity(quantity);
    String date = InventoryPresentation.date(expiry);
    try {
      JSONObject payload =
          new JSONObject()
              .put("ingredientId", ingredient.getId().toString())
              .put("quantity", amount)
              .put("unit", ingredient.getUnit().getValue())
              .put("location", location)
              .put("expiresOn", date == null ? JSONObject.NULL : date);
      submit("createInventoryBatch", payload);
    } catch (org.json.JSONException error) {
      throw new AcValidationException(AcMessage.of(R.string.ac_l10n_inventory_invalid_entry), error);
    }
  }

  void createNamed(String name, Unit unit, String quantity, String location, String expiry) {
    String typed = name == null ? "" : name.trim();
    if (typed.isEmpty() || typed.codePointCount(0, typed.length()) > 120)
      throw new AcValidationException(AcMessage.of(R.string.ac_l10n_inventory_name_length));
    if (unit == null)
      throw new AcValidationException(AcMessage.of(R.string.ac_l10n_inventory_choose_unit));
    String amount = InventoryPresentation.positiveQuantity(quantity);
    String date = InventoryPresentation.date(expiry);
    try {
      submit(
          "createIngredient",
          new JSONObject()
              .put("name", typed)
              .put("unit", unit.getValue())
              .put("quantity", amount)
              .put("location", location)
              .put("expiresOn", date == null ? JSONObject.NULL : date));
    } catch (org.json.JSONException error) {
      throw new AcValidationException(AcMessage.of(R.string.ac_l10n_inventory_invalid_entry), error);
    }
  }

  void change(InventoryBatch batch, String amount, ChangeReason reason) {
    String after =
        reason == ChangeReason.CORRECT
            ? InventoryPresentation.number(InventoryPresentation.quantity(amount))
            : InventoryPresentation.after(batch.getQuantity(), amount);
    if (reason == ChangeReason.CONSUME
        && (today() == null || InventoryPresentation.expired(batch, today())))
      throw new AcValidationException(
          AcMessage.of(R.string.ac_l10n_inventory_expired_consume));
    try {
      submit(
          "changeInventory",
          new JSONObject()
              .put("batchId", batch.getId().toString())
              .put("afterQuantity", after)
              .put("reason", reason.getValue())
              .put("expectedVersion", batch.getVersion()));
    } catch (org.json.JSONException error) {
      throw new AcValidationException(
          AcMessage.of(R.string.ac_l10n_inventory_invalid_quantity_op), error);
    }
  }

  private void submit(String operation, JSONObject payload) {
    if (busy || recovering)
      throw new AcValidationException(AcMessage.of(R.string.ac_l10n_inventory_processing));
    if (pending != null)
      throw new AcValidationException(AcMessage.of(R.string.ac_l10n_inventory_pending_first));
    if (!current() || userId == null || homeId == null)
      throw new AcValidationException(AcMessage.of(R.string.ac_l10n_inventory_no_household));
    busy = true;
    outcome = null;
    message = AcMessage.of(R.string.ac_l10n_inventory_submitting);
    changed();
    String key = UUID.randomUUID().toString();
    String owner = userId, home = homeId;
    calls
        .pendingWrites()
        .record(
            key,
            owner,
            home,
            operation,
            payload.toString(),
            null,
            null,
            new ApiCallback<Void>() {
              @Override
              public void onSuccess(Void value) {
                if (!current()) return;
                calls
                    .pendingWrites()
                    .listPending(
                        owner,
                        new ApiCallback<List<PendingWrite>>() {
                          @Override
                          public void onSuccess(List<PendingWrite> values) {
                            if (!current()) return;
                            pending =
                                values.stream()
                                    .filter(p -> p.key.equals(key))
                                    .findFirst()
                                    .orElse(null);
                            if (pending == null) {
                              busy = false;
                              fail("LOCAL_STORAGE_ERROR");
                              return;
                            }
                            dispatch();
                          }

                          @Override
                          public void onError(ApiError error) {
                            busy = false;
                            fail(error.code);
                          }
                        });
              }

              @Override
              public void onError(ApiError error) {
                busy = false;
                if (current()) fail(error.code);
              }
            });
  }

  void retry() {
    if (pending == null || busy || !current()) return;
    busy = true;
    message = AcMessage.of(R.string.ac_l10n_inventory_checking);
    changed();
    dispatch();
  }

  private void dispatch() {
    final PendingWrite write = pending;
    if (write == null || !current()) {
      busy = false;
      return;
    }
    try {
      JSONObject p = new JSONObject(write.canonicalPayload);
      Call<?> call;
      if ("createIngredient".equals(write.operationId))
        call =
            api.inventory()
                .createIngredient(
                    UUID.fromString(write.key),
                    new CreateIngredientRequest()
                        .name(p.getString("name"))
                        .unit(Unit.fromValue(p.getString("unit"))));
      else if ("createInventoryBatch".equals(write.operationId))
        call =
            api.inventory()
                .createInventoryBatch(
                    UUID.fromString(homeId),
                    UUID.fromString(write.key),
                    new CreateInventoryBatchRequest()
                        .ingredientId(UUID.fromString(p.getString("ingredientId")))
                        .quantity(p.getString("quantity"))
                        .unit(Unit.fromValue(p.getString("unit")))
                        .location(p.getString("location"))
                        .expiresOn(p.isNull("expiresOn") ? null : p.getString("expiresOn")));
      else
        call =
            api.inventory()
                .changeInventory(
                    UUID.fromString(homeId),
                    UUID.fromString(p.getString("batchId")),
                    UUID.fromString(write.key),
                    new InventoryChangeRequest()
                        .afterQuantity(p.getString("afterQuantity"))
                        .reason(ChangeReason.fromValue(p.getString("reason")))
                        .expectedVersion(p.getLong("expectedVersion")));
      send(call, write);
    } catch (RuntimeException | org.json.JSONException error) {
      busy = false;
      message = AcMessage.of(R.string.ac_l10n_inventory_pending_invalid);
      changed();
    }
  }

  private <T> void send(Call<T> call, PendingWrite write) {
    requests.add(call);
    calls.enqueue(
        call,
        new ApiCallback<T>() {
          @Override
          public void onSuccess(T value) {
            requests.remove(call);
            if (!current()) return;
            if (value instanceof Ingredient item && "createIngredient".equals(write.operationId)) {
              ingredients.put(item.getId(), item);
              continueIngredientCreation(item, write);
              return;
            }
            if (value instanceof InventoryBatch batch
                && "createInventoryBatch".equals(write.operationId)) {
              selectedId = batch.getId().toString();
              filter = "ALL";
              int shelf = InventoryPresentation.shelf(batch.getLocation());
              if (shelf >= 0 && shelf < 3) coldOpen = true;
              else if (shelf >= 3) freezeOpen = true;
            }
            finish(write, false, null);
          }

          @Override
          public void onError(ApiError error) {
            requests.remove(call);
            if (!current()) {
              clearVisible();
              return;
            }
            if (Arrays.asList(
                    "NETWORK_ERROR",
                    "TIMEOUT",
                    "CANCELLED",
                    "INVALID_RESPONSE",
                    "TEMPORARILY_UNAVAILABLE",
                    "INTERNAL_ERROR")
                .contains(error.code)) {
              busy = false;
              message =
                  AcMessage.of(
                      R.string.ac_l10n_inventory_unconfirmed_retry,
                      AcMessage.of(errorText(error.code)));
              changed();
            } else finish(write, false, error.code);
          }
        });
  }

  /**
   * Ends a pending write. {@code abandoned} marks a user-confirmed end; {@code errorCode} is null
   * on success. Both decisions are explicit so no localized text is ever used as a control value.
   */
  private void finish(PendingWrite write, boolean abandoned, String errorCode) {
    List<String> keys = new ArrayList<>();
    try {
      JSONObject p = new JSONObject(write.canonicalPayload);
      if (p.has("ingredientRequestKey")) keys.add(p.getString("ingredientRequestKey"));
      if ("createIngredient".equals(write.operationId) && abandoned) keys.add(childKey(write.key));
    } catch (org.json.JSONException invalid) {
      if (!abandoned) {
        busy = false;
        message = AcMessage.of(R.string.ac_l10n_inventory_pending_invalid_end);
        changed();
        return;
      }
    }
    keys.add(write.key);
    confirmKeys(
        write.userId,
        keys,
        0,
        () -> {
          pending = null;
          busy = false;
          load();
          outcome =
              abandoned
                  ? AcMessage.of(R.string.ac_l10n_inventory_pending_ended)
                  : errorCode == null
                      ? AcMessage.of(R.string.ac_l10n_inventory_operation_saved)
                      : AcMessage.of(errorText(errorCode));
          message = outcome;
          changed();
        });
  }

  private static String childKey(String ingredientKey) {
    return UUID.nameUUIDFromBytes(
            (ingredientKey + ":inventory").getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .toString();
  }

  private void continueIngredientCreation(Ingredient item, PendingWrite parent) {
    try {
      JSONObject p = new JSONObject(parent.canonicalPayload);
      JSONObject body =
          new JSONObject()
              .put("ingredientId", item.getId().toString())
              .put("unit", item.getUnit().getValue())
              .put("quantity", p.getString("quantity"))
              .put("location", p.getString("location"))
              .put("expiresOn", p.isNull("expiresOn") ? JSONObject.NULL : p.getString("expiresOn"))
              .put("ingredientRequestKey", parent.key);
      String child = childKey(parent.key);
      message = AcMessage.of(R.string.ac_l10n_inventory_ingredient_created);
      changed();
      calls
          .pendingWrites()
          .listPending(
              parent.userId,
              new ApiCallback<List<PendingWrite>>() {
                @Override
                public void onSuccess(List<PendingWrite> values) {
                  if (!current()) return;
                  PendingWrite found =
                      values.stream().filter(w -> w.key.equals(child)).findFirst().orElse(null);
                  if (found != null) {
                    if (!found.operationId.equals("createInventoryBatch")
                        || !found.canonicalPayload.equals(body.toString())
                        || !Objects.equals(found.householdId, parent.householdId)) {
                      childFailure();
                      return;
                    }
                    activateChild(parent, found);
                    return;
                  }
                  calls
                      .pendingWrites()
                      .record(
                          child,
                          parent.userId,
                          parent.householdId,
                          "createInventoryBatch",
                          body.toString(),
                          null,
                          null,
                          new ApiCallback<Void>() {
                            @Override
                            public void onSuccess(Void value) {
                              if (current()) continueIngredientCreation(item, parent);
                            }

                            @Override
                            public void onError(ApiError error) {
                              if (current()) childFailure();
                            }
                          });
                }

                @Override
                public void onError(ApiError error) {
                  if (current()) childFailure();
                }
              });
    } catch (org.json.JSONException invalid) {
      childFailure();
    }
  }

  private void activateChild(PendingWrite parent, PendingWrite child) {
    calls
        .pendingWrites()
        .confirm(
            parent.userId,
            parent.key,
            new ApiCallback<Void>() {
              @Override
              public void onSuccess(Void value) {
                if (current()) {
                  pending = child;
                  dispatch();
                }
              }

              @Override
              public void onError(ApiError error) {
                if (current()) childFailure();
              }
            });
  }

  private void childFailure() {
    busy = false;
    message = AcMessage.of(R.string.ac_l10n_inventory_ingredient_pending);
    changed();
  }

  private void confirmKeys(String owner, List<String> keys, int index, Runnable done) {
    if (!current()) return;
    if (index == keys.size()) {
      done.run();
      return;
    }
    calls
        .pendingWrites()
        .confirm(
            owner,
            keys.get(index),
            new ApiCallback<Void>() {
              @Override
              public void onSuccess(Void value) {
                confirmKeys(owner, keys, index + 1, done);
              }

              @Override
              public void onError(ApiError error) {
                if (!current()) return;
                busy = false;
                message = AcMessage.of(R.string.ac_l10n_inventory_local_confirm_failed);
                changed();
              }
            });
  }

  void abandon() {
    if (pending == null || busy || !current()) return;
    finish(pending, true, null);
  }

  void update(InventoryBatch batch, String location, String expiry) {
    if (busy || pending != null)
      throw new AcValidationException(AcMessage.of(R.string.ac_l10n_inventory_handle_current));
    String date = InventoryPresentation.date(expiry);
    busy = true;
    changed();
    Call<InventoryBatch> call =
        api.inventory()
            .updateInventoryBatch(
                UUID.fromString(homeId),
                batch.getId(),
                new UpdateInventoryBatchRequest()
                    .location(location)
                    .expiresOn(date)
                    .expectedVersion(batch.getVersion()));
    requests.add(call);
    calls.enqueue(
        call,
        new ApiCallback<InventoryBatch>() {
          @Override
          public void onSuccess(InventoryBatch value) {
            requests.remove(call);
            if (!current()) return;
            busy = false;
            load();
          }

          @Override
          public void onError(ApiError error) {
            requests.remove(call);
            if (!current()) {
              clearVisible();
              return;
            }
            busy = false;
            load();
            outcome =
                AcMessage.of(
                    R.string.ac_l10n_inventory_update_failed, AcMessage.of(errorText(error.code)));
            message = outcome;
            changed();
          }
        });
  }

  private void fail(String code) {
    loading = false;
    message = AcMessage.of(errorText(code));
    changed();
  }

  static int errorText(String code) {
    return switch (code) {
      case "VERSION_CONFLICT" -> R.string.ac_l10n_error_version_conflict;
      case "NETWORK_ERROR", "TIMEOUT" -> R.string.ac_l10n_error_network;
      case "LOCAL_STORAGE_ERROR" -> R.string.ac_l10n_error_local_storage;
      case "HOUSEHOLD_ACCESS_REVOKED" -> R.string.ac_l10n_error_access_revoked;
      case "HOUSEHOLD_REQUIRED" -> R.string.ac_l10n_error_household_required;
      case "UNIT_CONFLICT" -> R.string.ac_l10n_error_unit_conflict;
      case "INGREDIENT_INACTIVE" -> R.string.ac_l10n_error_ingredient_inactive;
      case "NAME_CONFLICT" -> R.string.ac_l10n_error_name_conflict;
      case "VALIDATION_ERROR" -> R.string.ac_l10n_error_validation;
      default -> R.string.ac_l10n_error_generic;
    };
  }

  @Override
  protected void onCleared() {
    closed = true;
    cancelReads();
  }
}
