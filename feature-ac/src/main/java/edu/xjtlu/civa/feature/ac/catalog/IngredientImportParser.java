package edu.xjtlu.civa.feature.ac.catalog;

import edu.xjtlu.civa.api.model.Ingredient;
import edu.xjtlu.civa.api.model.Unit;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict, testable conversion of free-text catalogue/personal ingredients into household amounts.
 *
 * <p>Only exact canonical names or aliases are resolved. A/B lines have a positive amount in the
 * ingredient's canonical unit. C lines keep their source text and must be corrected by the user;
 * this parser never substitutes 1 or 0 and never turns an unknown amount into trackStock=false.
 */
public final class IngredientImportParser {
    public enum Kind { DIRECT, CONVERT, REVIEW }

    public enum Reason {
        OK, NO_INGREDIENT, AMBIGUOUS_INGREDIENT, MISSING_QUANTITY, INVALID_QUANTITY,
        MISSING_UNIT, UNKNOWN_UNIT, UNIT_MISMATCH, APPROXIMATE, DUPLICATE, OUT_OF_RANGE,
        CONFLICTING_MEASUREMENT
    }

    /** All original fields are kept so the review UI can show what was written. */
    public record Input(String name, String quantity, String quantityPrefix, String unit,
                        String note, String sourceText) { }

    public record Line(Input source, UUID ingredientId, String ingredientName, String quantity,
                       Unit unit, Kind kind, Reason reason) {
        public boolean ready() { return kind != Kind.REVIEW; }
    }

    public record Result(List<Line> lines) {
        public boolean ready() {
            if (lines.isEmpty()) return false;
            for (Line line : lines) if (!line.ready()) return false;
            return true;
        }
    }

    private static final BigDecimal MAX = new BigDecimal("999999999999999.999");
    private static final Pattern QUANTITY =
            Pattern.compile("^(0|[1-9][0-9]{0,14})(\\.[0-9]{1,3})?$");
    private static final Pattern MEASUREMENT =
            Pattern.compile("^(.*?)\\s*([0-9]+(?:\\.[0-9]{1,3})?)\\s*([A-Za-z\\u4e00-\\u9fa5]+)$");

    private IngredientImportParser() { }

    public static Result parse(List<Input> inputs, List<Ingredient> dictionary) {
        Dictionary index = new Dictionary(dictionary);
        List<Line> lines = new ArrayList<>();
        for (Input input : inputs == null ? List.<Input>of() : inputs) {
            lines.add(parseOne(input == null ? new Input(null, null, null, null, null, null) : input, index));
        }
        markDuplicates(lines);
        return new Result(List.copyOf(lines));
    }

    private static Line parseOne(Input input, Dictionary index) {
        String name = trim(input.name());
        String quantityText = trim(input.quantity());
        String unitText = trim(input.unit());
        String prefix = trim(input.quantityPrefix());
        String note = trim(input.note());
        String sourceText = trim(input.sourceText());

        String candidate = name;
        String measuredQuantity = null;
        String measuredUnit = null;
        Measurement fromName = measurement(name);
        if (fromName != null) {
            candidate = fromName.base();
            measuredQuantity = fromName.quantity();
            measuredUnit = fromName.unit();
        }
        Resolved resolved = index.resolve(candidate);
        if (resolved.id == null && !sourceText.isEmpty()) {
            Measurement fromSource = measurement(sourceText);
            if (fromSource != null) {
                Resolved sourceResolved = index.resolve(fromSource.base());
                if (sourceResolved.id != null || sourceResolved.ambiguous) {
                    resolved = sourceResolved;
                    candidate = fromSource.base();
                    measuredQuantity = fromSource.quantity();
                    measuredUnit = fromSource.unit();
                }
            }
        }
        if (resolved.id == null && resolved.ambiguous) {
            return review(input, null, null, Kind.REVIEW, Reason.AMBIGUOUS_INGREDIENT);
        }
        if (resolved.id == null) {
            return review(input, null, null, Kind.REVIEW, Reason.NO_INGREDIENT);
        }
        if ((quantityText.isEmpty() || unitText.isEmpty()) && !sourceText.isEmpty()) {
            Measurement fromSource = measurement(sourceText);
            if (fromSource != null) {
                Resolved sourceResolved = index.resolve(fromSource.base());
                if (sourceResolved.id != null && sourceResolved.id.equals(resolved.id)) {
                    if (quantityText.isEmpty()) measuredQuantity = fromSource.quantity();
                    if (unitText.isEmpty()) measuredUnit = fromSource.unit();
                }
            }
        }
        Ingredient ingredient = index.byId.get(resolved.id);
        String ingredientName = ingredient.getName();
        Unit target = ingredient.getUnit();
        if (target == null) return review(input, resolved.id, ingredientName, Kind.REVIEW, Reason.MISSING_UNIT);

        if (quantityText.isEmpty()) quantityText = measuredQuantity == null ? "" : measuredQuantity;
        if (unitText.isEmpty()) unitText = measuredUnit == null ? "" : measuredUnit;

        // A dirty "面粉 200g" name can disagree with separately provided fields; never pick silently.
        if (fromName != null && !trim(input.quantity()).isEmpty()
                && !sameNumber(fromName.quantity(), input.quantity())) {
            return review(input, resolved.id, ingredientName, Kind.REVIEW, Reason.CONFLICTING_MEASUREMENT);
        }
        if (fromName != null && !trim(input.unit()).isEmpty()
                && !normalizeUnit(fromName.unit()).equals(normalizeUnit(input.unit()))) {
            return review(input, resolved.id, ingredientName, Kind.REVIEW, Reason.CONFLICTING_MEASUREMENT);
        }

        if (!prefix.isEmpty() || approximate(note) || approximate(sourceText)) {
            return review(input, resolved.id, ingredientName, Kind.REVIEW, Reason.APPROXIMATE);
        }
        if (quantityText.isEmpty()) {
            return review(input, resolved.id, ingredientName, Kind.REVIEW, Reason.MISSING_QUANTITY);
        }
        BigDecimal quantity = number(quantityText);
        if (quantity == null) {
            return review(input, resolved.id, ingredientName, Kind.REVIEW,
                    outOfRange(quantityText) ? Reason.OUT_OF_RANGE : Reason.INVALID_QUANTITY);
        }
        if (quantity.signum() <= 0) {
            return review(input, resolved.id, ingredientName, Kind.REVIEW, Reason.INVALID_QUANTITY);
        }
        if (unitText.isEmpty()) {
            return review(input, resolved.id, ingredientName, Kind.REVIEW, Reason.MISSING_UNIT);
        }

        UnitKind source = unitKind(unitText);
        if (source == UnitKind.UNKNOWN) {
            return review(input, resolved.id, ingredientName, Kind.REVIEW, Reason.UNKNOWN_UNIT);
        }
        if (source.matches(target)) {
            return ready(input, resolved.id, ingredientName, quantity, target, Kind.DIRECT);
        }
        if ((source == UnitKind.KG && target == Unit.G) || (source == UnitKind.L && target == Unit.ML)) {
            BigDecimal converted = quantity.multiply(new BigDecimal("1000"));
            if (!valid(converted)) {
                return review(input, resolved.id, ingredientName, Kind.REVIEW, Reason.OUT_OF_RANGE);
            }
            return ready(input, resolved.id, ingredientName, converted, target, Kind.CONVERT);
        }
        return review(input, resolved.id, ingredientName, Kind.REVIEW, Reason.UNIT_MISMATCH);
    }

    private static Line ready(Input input, UUID id, String name, BigDecimal quantity, Unit unit, Kind kind) {
        return new Line(input, id, name, text(quantity), unit, kind, Reason.OK);
    }

    private static Line review(Input input, UUID id, String name, Kind kind, Reason reason) {
        return new Line(input, id, name, null, null, kind, reason);
    }

    private static void markDuplicates(List<Line> lines) {
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        for (Line line : lines) {
            if (line.ingredientId() != null) counts.merge(line.ingredientId(), 1, Integer::sum);
        }
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (line.ingredientId() != null && counts.getOrDefault(line.ingredientId(), 0) > 1) {
                lines.set(i, new Line(line.source(), line.ingredientId(), line.ingredientName(),
                        line.quantity(), line.unit(), Kind.REVIEW, Reason.DUPLICATE));
            }
        }
    }

    private static boolean approximate(String text) {
        if (text == null || text.isEmpty()) return false;
        return text.contains("适量") || text.contains("少许") || text.contains("一小把")
                || text.contains("一把") || text.contains("若干") || text.contains("约")
                || text.contains("大约");
    }

    private static boolean outOfRange(String text) {
        if (text == null) return false;
        try {
            return new BigDecimal(text).compareTo(MAX) > 0;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    /** Strict positive number with <= 3 decimals and the database's numeric(18,3) range. */
    public static BigDecimal number(String text) {
        String value = trim(text);
        if (!QUANTITY.matcher(value).matches()) return null;
        try {
            BigDecimal parsed = new BigDecimal(value);
            return valid(parsed) ? parsed : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    public static boolean valid(BigDecimal value) {
        return value != null && value.signum() >= 0 && value.scale() <= 3 && value.compareTo(MAX) <= 0;
    }

    public static String text(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static boolean sameNumber(String left, String right) {
        BigDecimal a = number(left);
        BigDecimal b = number(right);
        return a != null && b != null && a.compareTo(b) == 0;
    }

    private static Measurement measurement(String text) {
        String value = trim(text);
        if (value.isEmpty()) return null;
        Matcher matcher = MEASUREMENT.matcher(value);
        if (!matcher.matches()) return null;
        String base = matcher.group(1).trim();
        if (base.isEmpty()) return null;
        return new Measurement(base, matcher.group(2), matcher.group(3));
    }

    private static UnitKind unitKind(String unit) {
        String value = normalizeUnit(unit);
        return switch (value) {
            case "g", "克", "gram", "grams" -> UnitKind.G;
            case "kg", "千克", "公斤", "kilogram", "kilograms" -> UnitKind.KG;
            case "ml", "毫升", "milliliter", "milliliters" -> UnitKind.ML;
            case "l", "升", "liter", "liters" -> UnitKind.L;
            case "个", "只", "颗", "count" -> UnitKind.COUNT;
            default -> UnitKind.UNKNOWN;
        };
    }

    private static String normalizeUnit(String unit) {
        return trim(unit).toLowerCase(Locale.ROOT).replace(" ", "");
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class Dictionary {
        final Map<String, Set<UUID>> ids = new HashMap<>();
        final Map<UUID, Ingredient> byId = new LinkedHashMap<>();

        Dictionary(List<Ingredient> ingredients) {
            for (Ingredient ingredient : ingredients == null ? List.<Ingredient>of() : ingredients) {
                if (ingredient == null || ingredient.getId() == null) continue;
                byId.put(ingredient.getId(), ingredient);
                add(ingredient.getName(), ingredient.getId());
                if (ingredient.getAliases() != null) {
                    for (String alias : ingredient.getAliases()) add(alias, ingredient.getId());
                }
            }
        }

        void add(String name, UUID id) {
            String key = trim(name).toLowerCase(Locale.ROOT);
            if (key.isEmpty()) return;
            ids.computeIfAbsent(key, ignored -> new HashSet<>()).add(id);
        }

        Resolved resolve(String name) {
            String key = trim(name).toLowerCase(Locale.ROOT);
            Set<UUID> found = ids.get(key);
            if (found == null || found.isEmpty()) return new Resolved(null, false);
            if (found.size() > 1) return new Resolved(null, true);
            return new Resolved(found.iterator().next(), false);
        }
    }

    private record Resolved(UUID id, boolean ambiguous) { }

    private record Measurement(String base, String quantity, String unit) { }

    private enum UnitKind {
        G, ML, COUNT, KG, L, UNKNOWN;

        boolean matches(Unit target) {
            return (this == G && target == Unit.G) || (this == ML && target == Unit.ML)
                    || (this == COUNT && target == Unit.COUNT);
        }
    }
}
