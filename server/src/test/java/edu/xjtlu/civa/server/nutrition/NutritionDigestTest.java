package edu.xjtlu.civa.server.nutrition;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NutritionDigestTest {
    private static NutritionProvider.Input input(String name, List<String> ingredients, List<String> steps) {
        return new NutritionProvider.Input(name, ingredients, steps);
    }

    @Test void digestIsStableLowercaseSha256() {
        String first = NutritionDigest.hash(input("番茄炒蛋", List.of("番茄 2 个"), List.of("合炒")));
        assertEquals(first, NutritionDigest.hash(input("番茄炒蛋", List.of("番茄 2 个"), List.of("合炒"))));
        assertTrue(first.matches("[0-9a-f]{64}"), first);
    }

    @Test void whitespaceAndBlankEntriesDoNotChangeTheDigest() {
        String plain = NutritionDigest.hash(input("番茄炒蛋", List.of("番茄 2 个", "鸡蛋 3 个"), List.of("合炒")));
        String padded = NutritionDigest.hash(input(" 番茄炒蛋 ", List.of(" 番茄 2 个 ", "鸡蛋  3 个", " "), List.of(" 合炒 ", "  ")));
        assertEquals(plain, padded);
    }

    @Test void differentDishContentProducesDifferentDigests() {
        assertNotEquals(
                NutritionDigest.hash(input("番茄炒蛋", List.of("番茄 2 个"), List.of("合炒"))),
                NutritionDigest.hash(input("番茄炒蛋", List.of("番茄 2 个", "鸡蛋 3 个"), List.of("合炒"))));
        assertNotEquals(
                NutritionDigest.hash(input("番茄炒蛋", List.of("番茄 2 个"), List.of("合炒"))),
                NutritionDigest.hash(input("番茄蛋汤", List.of("番茄 2 个"), List.of("合炒"))));
    }

    @Test void normalizationTrimsCollapsesAndDropsBlanks() {
        assertEquals(List.of("番茄 2 个", "鸡蛋 3 个"),
                NutritionDigest.normalize(Arrays.asList(" 番茄  2 个 ", null, "   ", "鸡蛋 3 个")));
        assertEquals(List.of(), NutritionDigest.normalize(null));
    }
}
