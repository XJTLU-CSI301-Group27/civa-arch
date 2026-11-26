package edu.xjtlu.civa.feature.ac.inventory;

import android.content.res.Resources;
import edu.xjtlu.civa.api.model.Ingredient;
import edu.xjtlu.civa.api.model.InventoryBatch;
import edu.xjtlu.civa.api.model.Unit;
import edu.xjtlu.civa.core.ui.QuantityInputs;
import edu.xjtlu.civa.feature.ac.AcMessage;
import edu.xjtlu.civa.feature.ac.AcText;
import edu.xjtlu.civa.feature.ac.AcValidationException;
import edu.xjtlu.civa.feature.ac.R;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;

/** Feature-local presentation and input rules; canonical units always come from the API. */
final class InventoryPresentation {
  // i18n: stored location values (data sent to the API and matched in shelf()); the screen shows
  // ac_l10n_location_* labels for the same indices.
  static final String[] LOCATIONS = {"冷藏 · 蛋奶层", "冷藏 · 蔬菜层", "冷藏 · 水果层", "冷冻 · 肉类抽屉", "冷冻 · 海鲜抽屉"};

  private InventoryPresentation() {}

  static String number(BigDecimal value) {
    return value.stripTrailingZeros().toPlainString();
  }

  static BigDecimal quantity(String raw) {
    if (raw == null || raw.trim().isEmpty())
      throw new AcValidationException(AcMessage.of(R.string.ac_l10n_validate_quantity_empty));
    if (!raw.trim().matches("[0-9]+(\\.[0-9]{1,3})?"))
      throw new AcValidationException(AcMessage.of(R.string.ac_l10n_validate_quantity_format));
    BigDecimal value;
    try {
      value = new BigDecimal(raw.trim());
    } catch (NumberFormatException error) {
      throw new AcValidationException(
          AcMessage.of(R.string.ac_l10n_validate_quantity_invalid), error);
    }
    try {
      return QuantityInputs.toBase(value, "G").amount;
    } catch (IllegalArgumentException overflow) {
      throw new AcValidationException(
          AcMessage.of(R.string.ac_l10n_validate_quantity_range), overflow);
    }
  }

  static String positiveQuantity(String raw) {
    BigDecimal value = quantity(raw);
    if (value.signum() <= 0)
      throw new AcValidationException(AcMessage.of(R.string.ac_l10n_validate_quantity_positive));
    return number(value);
  }

  static boolean inFridge(InventoryBatch batch) {
    // Unknown must stay unknown, rather than being silently classified as empty.
    return batch.getQuantity() == null || quantity(batch.getQuantity()).signum() > 0;
  }

  static boolean otherBatchesRemain(InventoryBatch target, Iterable<InventoryBatch> batches) {
    for (InventoryBatch batch : batches)
      if (!batch.getId().equals(target.getId())
          && batch.getIngredientId().equals(target.getIngredientId())
          && inFridge(batch)) return true;
    return false;
  }

  // Input-only choices; the shared API still stores G / COUNT / ML.
  static Unit inputBaseUnit(int choice) {
    return switch (choice) {
      case 1, 4 -> Unit.G;
      case 2 -> Unit.COUNT;
      case 3 -> Unit.ML;
      default -> null;
    };
  }

  static String inputQuantity(String raw, int choice) {
    Unit base = inputBaseUnit(choice);
    if (base == null)
      throw new AcValidationException(AcMessage.of(R.string.ac_l10n_validate_choose_unit));
    BigDecimal value = new BigDecimal(positiveQuantity(raw));
    try {
      return number(QuantityInputs.toBase(value, choice == 4 ? "KG" : base.getValue()).amount);
    } catch (IllegalArgumentException overflow) {
      throw new AcValidationException(
          AcMessage.of(R.string.ac_l10n_validate_convert_range), overflow);
    }
  }

  static int suggestedShelf(String name) {
    String english = name.toLowerCase(Locale.ROOT);
    // i18n: matching keyword - Chinese ingredient names only classify the default shelf.
    if (name.contains("奶")
        || name.contains("蛋")
        || english.matches(
            ".*\\b(milk|egg|eggs|yogurt|yoghurt|cheese|butter|cream|water|juice)\\b.*")) return 0;
    if (name.contains("虾")
        || name.contains("贝")
        || name.contains("鱼")
        || english.matches(
            ".*\\b(shrimp|shrimps|prawn|prawns|fish|salmon|tuna|cod|crab|crabs|lobster|lobsters|scallop|scallops)\\b.*"))
      return 4;
    if (name.contains("肉")
        || english.matches(
            ".*\\b(beef|pork|lamb|mutton|chicken|duck|turkey|meat|bacon|ham|steak|sausage|sausages)\\b.*"))
      return 3;
    if (name.contains("苹果")
        || name.contains("梨")
        || name.contains("香蕉")
        || name.contains("葡萄")
        || name.contains("橙")
        || name.contains("橘")
        || english.matches(
            ".*\\b(apple|apples|pear|pears|banana|bananas|grape|grapes|peach|peaches|"
                + "orange|oranges|lemon|lemons|lime|limes|kiwi|kiwis|mango|mangoes|mangos|"
                + "strawberry|strawberries|blueberry|blueberries|cherry|cherries|"
                + "watermelon|melon|pineapple|plum|plums|fruit)\\b.*")) return 2;
    return 1;
  }

  static String after(String before, String consumed) {
    BigDecimal amount = quantity(consumed), stock = quantity(before);
    if (amount.signum() <= 0 || amount.compareTo(stock) > 0)
      throw new AcValidationException(AcMessage.of(R.string.ac_l10n_validate_consume_range));
    return number(stock.subtract(amount));
  }

  static String date(String raw) {
    if (raw == null || raw.trim().isEmpty()) return null;
    try {
      String value = raw.trim();
      if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();
      return LocalDate.parse(value).toString();
    } catch (RuntimeException error) {
      throw new AcValidationException(AcMessage.of(R.string.ac_l10n_validate_date), error);
    }
  }

  static int shelf(String location) {
    if (location == null) return -1;
    for (int i = 0; i < LOCATIONS.length; i++) if (LOCATIONS[i].equals(location)) return i;
    // i18n: matching keyword - legacy stored locations may carry the Chinese zone word.
    if (location.contains("冷冻")) return 3;
    if (location.contains("冷藏")) return 1;
    return -1;
  }

  static String name(Resources resources, InventoryBatch batch, Ingredient ingredient) {
    return ingredient == null
        ? resources.getString(
            R.string.ac_l10n_inventory_unloaded_name,
            batch.getIngredientId().toString().substring(0, 8))
        : ingredient.getName();
  }

  /** Localized display label for the stored unit value. */
  static String unit(Resources resources, Unit unit, String name) {
    return AcText.unit(resources, unit, name);
  }

  /** Legacy text-only label kept for JVM unit tests; screens use {@link #unit(Resources, Unit, String)}. */
  static String unit(Unit unit, String name) {
    if (unit == Unit.G) return "克";
    if (unit == Unit.ML) return "毫升";
    if (unit == Unit.COUNT)
      return name.contains("奶")
              || name.toLowerCase(Locale.ROOT).matches(".*\\b(milk|yogurt|yoghurt)\\b.*")
          ? "瓶"
          : "个";
    return "单位未知";
  }

  static String emoji(String name) {
    String english = name.toLowerCase(Locale.ROOT);
    if (english.matches(".*\\b(beef|pork|lamb|mutton|chicken|meat)\\b.*")) return "🥩";
    if (english.matches(".*\\b(egg|eggs)\\b.*")) return "🥚";
    if (english.matches(".*\\b(milk|yogurt|yoghurt)\\b.*")) return "🥛";
    if (english.matches(".*\\b(shrimp|prawn)\\b.*")) return "🦐";
    if (english.matches(".*\\b(fish|scallop|scallops)\\b.*")) return "🐟";
    if (english.matches(".*\\b(apple|apples)\\b.*")) return "🍎";
    // i18n: matching keyword - Chinese ingredient names only choose the illustration.
    if (english.matches(".*\\b(pear|pears)\\b.*") || name.contains("梨")) return "🍐";
    if (english.matches(".*\\b(banana|bananas)\\b.*") || name.contains("香蕉")) return "🍌";
    if (english.matches(".*\\b(grape|grapes)\\b.*") || name.contains("葡萄")) return "🍇";
    if (english.matches(".*\\b(orange|oranges)\\b.*")) return "🍊";
    if (name.contains("虾")) return "🦐";
    if (name.contains("蛋")) return "🥚";
    if (name.contains("奶")) return "🥛";
    if (name.contains("番茄") || name.contains("西红柿")) return "🍅";
    if (name.contains("西兰花")) return "🥦";
    if (name.contains("苹果")) return "🍎";
    if (name.contains("橙") || name.contains("橘")) return "🍊";
    if (name.contains("肉")) return "🥩";
    if (name.contains("腩") || name.contains("排骨") || name.contains("五花")) return "🥩";
    if (name.contains("鱼")) return "🐟";
    if (name.contains("贝")) return "🐚";
    return switch (suggestedShelf(name)) {
      case 0 -> "🥛";
      case 2 -> "🍉";
      case 3 -> "🥩";
      case 4 -> "🐟";
      default -> "🥬";
    };
  }

  /** Localized expiry status for the fridge screens. */
  static String expiry(Resources resources, InventoryBatch batch, LocalDate today) {
    if (batch.getQuantity() != null && quantity(batch.getQuantity()).signum() == 0)
      return resources.getString(R.string.ac_l10n_inventory_used_up);
    if (batch.getExpiresOn() == null)
      return resources.getString(R.string.ac_l10n_inventory_expiry_unknown);
    LocalDate expiry = LocalDate.parse(batch.getExpiresOn());
    long days = java.time.temporal.ChronoUnit.DAYS.between(today, expiry);
    if (days < 0) return resources.getString(R.string.ac_l10n_inventory_expired);
    if (days == 0) return resources.getString(R.string.ac_l10n_inventory_expires_today);
    if (days <= 3) return resources.getString(R.string.ac_l10n_inventory_expiring_days, days);
    return resources.getString(R.string.ac_l10n_inventory_expires_on_date, expiry.toString());
  }

  /** Legacy text-only status kept for JVM unit tests; screens use {@link #expiry(Resources, InventoryBatch, LocalDate)}. */
  static String expiry(InventoryBatch batch, LocalDate today) {
    if (batch.getQuantity() != null && quantity(batch.getQuantity()).signum() == 0) return "已用完";
    if (batch.getExpiresOn() == null) return "到期日期未知";
    LocalDate expiry = LocalDate.parse(batch.getExpiresOn());
    long days = java.time.temporal.ChronoUnit.DAYS.between(today, expiry);
    if (days < 0) return "已过期";
    if (days == 0) return "今天到期";
    if (days <= 3) return String.format(Locale.ROOT, "临期 · %d天后", days);
    return expiry.toString() + " 到期";
  }

  static boolean expired(InventoryBatch batch, LocalDate today) {
    return batch.getExpiresOn() != null && LocalDate.parse(batch.getExpiresOn()).isBefore(today);
  }
}
