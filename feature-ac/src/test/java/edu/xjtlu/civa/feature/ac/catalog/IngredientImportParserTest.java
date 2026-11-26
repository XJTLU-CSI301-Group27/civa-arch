package edu.xjtlu.civa.feature.ac.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import edu.xjtlu.civa.api.model.Ingredient;
import edu.xjtlu.civa.api.model.Unit;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

public class IngredientImportParserTest {
    private static final UUID EGG = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID FLOUR = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID MILK = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private final Ingredient egg = ingredient(EGG, "鸡蛋", Unit.COUNT, List.of("蛋"));
    private final Ingredient flour = ingredient(FLOUR, "面粉", Unit.G, List.of());
    private final Ingredient milk = ingredient(MILK, "牛奶", Unit.ML, List.of());

    @Test public void directSameUnitIsAccepted() {
        assertReady("2", "个", egg, IngredientImportParser.Kind.DIRECT, "2", Unit.COUNT);
        assertReady("200", "克", flour, IngredientImportParser.Kind.DIRECT, "200", Unit.G);
        assertReady("100", "ml", milk, IngredientImportParser.Kind.DIRECT, "100", Unit.ML);
    }

    @Test public void deterministicKgAndLitreConversionsAreAccepted() {
        assertReady("1.5", "kg", flour, IngredientImportParser.Kind.CONVERT, "1500", Unit.G);
        assertReady("1", "L", milk, IngredientImportParser.Kind.CONVERT, "1000", Unit.ML);
        assertReady("1.5", "KG", flour, IngredientImportParser.Kind.CONVERT, "1500", Unit.G);
    }

    @Test public void spoonsAndApproximateAmountsNeedReview() {
        assertReview("2", "勺", egg, IngredientImportParser.Reason.UNKNOWN_UNIT);
        assertReview(null, "适量", egg, IngredientImportParser.Reason.MISSING_QUANTITY);
        assertReview("2", "少许", egg, IngredientImportParser.Reason.UNKNOWN_UNIT);
        assertReview(null, "一小把", egg, IngredientImportParser.Reason.MISSING_QUANTITY);
        assertReview("2", null, egg, IngredientImportParser.Reason.MISSING_UNIT);
        assertReview(null, "个", egg, IngredientImportParser.Reason.MISSING_QUANTITY);
    }

    @Test public void zeroNegativeAndInvalidAmountsAreRejected() {
        assertReview("0", "个", egg, IngredientImportParser.Reason.INVALID_QUANTITY);
        assertReview("-1", "个", egg, IngredientImportParser.Reason.INVALID_QUANTITY);
        assertReview("abc", "个", egg, IngredientImportParser.Reason.INVALID_QUANTITY);
        assertReview("1.2345", "个", egg, IngredientImportParser.Reason.INVALID_QUANTITY);
        assertReview("1000000000000000", "克", flour, IngredientImportParser.Reason.OUT_OF_RANGE);
    }

    @Test public void exactNamesAndAliasesMatchButContainedNamesDoNot() {
        assertEquals(EGG, parse("鸡蛋", "2", "个", List.of(egg)).lines().get(0).ingredientId());
        assertEquals(EGG, parse("蛋", "2", "个", List.of(egg)).lines().get(0).ingredientId());
        assertReview("鸡蛋液", "2", "个", List.of(egg), IngredientImportParser.Reason.NO_INGREDIENT);
    }

    @Test public void conflictingAliasesRequireAChoice() {
        Ingredient other = ingredient(UUID.randomUUID(), "鸭蛋", Unit.COUNT, List.of("蛋"));
        IngredientImportParser.Line line = parse("蛋", "2", "个", List.of(egg, other)).lines().get(0);

        assertEquals(IngredientImportParser.Reason.AMBIGUOUS_INGREDIENT, line.reason());
        assertNull(line.ingredientId());
    }

    @Test public void dimensionsAreNeverConvertedAcrossEachOther() {
        assertReview("2", "个", flour, IngredientImportParser.Reason.UNIT_MISMATCH);
        assertReview("200", "克", milk, IngredientImportParser.Reason.UNIT_MISMATCH);
        assertReview("1", "kg", milk, IngredientImportParser.Reason.UNIT_MISMATCH);
        assertReview("1", "L", flour, IngredientImportParser.Reason.UNIT_MISMATCH);
    }

    @Test public void repeatedIngredientIsFlaggedInsteadOfOverwritten() {
        IngredientImportParser.Result result = parse(List.of(
                input("鸡蛋", "2", "个", null, null, null),
                input("鸡蛋", "3", "个", null, null, null)), List.of(egg));

        assertEquals(2, result.lines().size());
        assertEquals(IngredientImportParser.Reason.DUPLICATE, result.lines().get(0).reason());
        assertEquals(IngredientImportParser.Reason.DUPLICATE, result.lines().get(1).reason());
        assertFalse(result.ready());
    }

    @Test public void sourceTextCanRecoverMeasurementWhenFieldsAreEmpty() {
        IngredientImportParser.Line line = parse(input("面粉", null, null, null, null, "面粉 200g"), List.of(flour))
                .lines().get(0);

        assertEquals(IngredientImportParser.Kind.DIRECT, line.kind());
        assertEquals("200", line.quantity());
        assertEquals(Unit.G, line.unit());
    }

    @Test public void dirtyNameWithMeasurementIsMatchedByItsBaseName() {
        IngredientImportParser.Line line = parse(input("面粉 200g", null, null, null, null, null), List.of(flour))
                .lines().get(0);

        assertEquals(IngredientImportParser.Kind.DIRECT, line.kind());
        assertEquals("200", line.quantity());
    }

    @Test public void conflictBetweenDirtyNameAndProvidedFieldsNeedsReview() {
        assertReview("面粉 200g", "300", "g", List.of(flour),
                IngredientImportParser.Reason.CONFLICTING_MEASUREMENT);
    }

    @Test public void prefixesAndNotesAreTreatedAsApproximate() {
        assertReview(input("鸡蛋", "2", "个", "约", null, null), List.of(egg),
                IngredientImportParser.Reason.APPROXIMATE);
        assertReview(input("鸡蛋", "2", "个", null, "少许", null), List.of(egg),
                IngredientImportParser.Reason.APPROXIMATE);
    }

    @Test public void emptyResultIsNotReady() {
        assertTrue(parse(List.of(), List.of(egg)).lines().isEmpty());
        assertFalse(parse(List.of(), List.of(egg)).ready());
    }

    @Test public void precisionAndDatabaseRangeAreExact() {
        assertEquals(new BigDecimal("999999999999999.999"),
                IngredientImportParser.number("999999999999999.999"));
        assertNull(IngredientImportParser.number("1000000000000000"));
        assertNull(IngredientImportParser.number("1.2345"));
        assertEquals("1500", IngredientImportParser.text(new BigDecimal("1500.000")));
    }

    private void assertReady(String quantity, String unit, Ingredient ingredient,
                             IngredientImportParser.Kind kind, String expectedQuantity, Unit expectedUnit) {
        IngredientImportParser.Line line = parse(ingredient.getName(), quantity, unit, List.of(ingredient)).lines().get(0);
        assertEquals(kind, line.kind());
        assertEquals(expectedQuantity, line.quantity());
        assertEquals(expectedUnit, line.unit());
        assertTrue(line.ready());
    }

    private IngredientImportParser.Result parse(String name, String quantity, String unit,
                                                List<Ingredient> dictionary) {
        return parse(input(name, quantity, unit, null, null, null), dictionary);
    }

    private IngredientImportParser.Result parse(IngredientImportParser.Input input, List<Ingredient> dictionary) {
        return parse(List.of(input), dictionary);
    }

    private IngredientImportParser.Result parse(List<IngredientImportParser.Input> inputs,
                                                List<Ingredient> dictionary) {
        return IngredientImportParser.parse(inputs, dictionary);
    }

    private IngredientImportParser.Line parseOne(String name, String quantity, String unit,
                                                 List<Ingredient> dictionary) {
        return parse(name, quantity, unit, dictionary).lines().get(0);
    }

    private void assertReview(String quantity, String unit, Ingredient ingredient,
                              IngredientImportParser.Reason reason) {
        IngredientImportParser.Line line = parseOne(ingredient.getName(), quantity, unit, List.of(ingredient));
        assertEquals(IngredientImportParser.Kind.REVIEW, line.kind());
        assertEquals(reason, line.reason());
        assertFalse(line.ready());
    }

    private void assertReview(IngredientImportParser.Input input, List<Ingredient> dictionary,
                              IngredientImportParser.Reason reason) {
        IngredientImportParser.Line line = parse(input, dictionary).lines().get(0);
        assertEquals(IngredientImportParser.Kind.REVIEW, line.kind());
        assertEquals(reason, line.reason());
        assertFalse(line.ready());
    }

    private void assertReview(String name, String quantity, String unit, List<Ingredient> dictionary,
                              IngredientImportParser.Reason reason) {
        IngredientImportParser.Line line = parseOne(name, quantity, unit, dictionary);
        assertEquals(IngredientImportParser.Kind.REVIEW, line.kind());
        assertEquals(reason, line.reason());
        assertFalse(line.ready());
    }

    private static IngredientImportParser.Input input(String name, String quantity, String unit,
                                                       String prefix, String note, String source) {
        return new IngredientImportParser.Input(name, quantity, prefix, unit, note, source);
    }

    private static Ingredient ingredient(UUID id, String name, Unit unit, List<String> aliases) {
        return new Ingredient().id(id).name(name).unit(unit).aliases(aliases).active(true);
    }
}
