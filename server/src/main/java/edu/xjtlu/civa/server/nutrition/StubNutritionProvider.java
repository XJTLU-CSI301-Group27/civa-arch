package edu.xjtlu.civa.server.nutrition;

import java.math.BigDecimal;

/**
 * Deterministic provider for tests and for environments without a provider key. Values depend
 * only on the dish content, so the same request always yields the same estimate.
 */
final class StubNutritionProvider implements NutritionProvider {
    @Override public String name() { return "stub"; }
    @Override public String version() { return "stub:v1"; }

    @Override public Estimate estimate(Input input) {
        int seed = Integer.parseInt(NutritionDigest.hash(input).substring(0, 6), 16);
        return new Estimate(
                BigDecimal.valueOf(60 + seed % 180),
                BigDecimal.valueOf((seed / 3) % 40 * 1.5),
                BigDecimal.valueOf((seed / 7) % 20 * 0.9),
                BigDecimal.valueOf((seed / 11) % 15 * 0.8),
                seed % 5 == 0 ? null : BigDecimal.valueOf((seed / 13) % 6),
                seed % 7 == 0 ? null : BigDecimal.valueOf((seed / 17) % 9),
                BigDecimal.valueOf(50 + seed % 400),
                "离线示例估算：数值仅供界面演示，实际摄入受用量与烹调方式影响。");
    }
}
