package edu.xjtlu.civa.feature.bd;

import android.content.Context;
import edu.xjtlu.civa.core.data.PlannedDish;
import edu.xjtlu.civa.core.data.SessionStore;
import edu.xjtlu.civa.core.data.TodayPlanReader;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Public read-only bridge: the personal today plan is local, but the food diary may show it. */
public final class BdTodayPlanReader implements TodayPlanReader {
    private final Context context;
    private final SessionStore sessions;

    public BdTodayPlanReader(Context context, SessionStore sessions) {
        this.context = context.getApplicationContext();
        this.sessions = sessions;
    }

    @Override public List<PlannedDish> plan(LocalDate date) {
        if (date == null || sessions == null || !sessions.isLoggedIn()) return List.of();
        String owner = "account:" + sessions.currentUserId();
        String scope = BdDraft.scopeHash(owner);
        try {
            BdDraftStore store = new BdDraftStore(context, scope);
            BdDraft draft = store.load();
            List<PlannedDish> result = new ArrayList<>();
            for (String meal : new String[]{"BREAKFAST", "LUNCH", "DINNER"}) {
                for (BdDraft.Dish dish : draft.dishes(date, meal)) {
                    if (dish.name == null || dish.name.trim().isEmpty()) continue;
                    result.add(new PlannedDish(meal, dish.name, dish.recipeId, dish.image,
                            List.copyOf(dish.ingredients), List.copyOf(dish.steps)));
                }
            }
            return result;
        } catch (Exception unavailable) {
            return List.of();
        }
    }
}
