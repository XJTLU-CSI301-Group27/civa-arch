package edu.xjtlu.civa.feature.ac.catalog;

import android.content.res.Resources;
import edu.xjtlu.civa.feature.ac.AcText;
import edu.xjtlu.civa.feature.ac.R;
import java.util.ArrayList;
import java.util.List;

/** Shared user-facing text for a RecipeStockPresenter result; max two shortages are shown. */
public final class RecipeStockText {
    private RecipeStockText() { }

    public static String format(Resources resources, RecipeStockPresenter.StockView view) {
        if (view == null || view.state() == RecipeStockPresenter.State.UNKNOWN) {
            return resources.getString(R.string.ac_stock_unknown);
        }
        if (view.state() == RecipeStockPresenter.State.ENOUGH) {
            return view.tracked() ? resources.getString(R.string.ac_stock_enough, view.servings())
                    : resources.getString(R.string.ac_stock_no_tracking);
        }
        List<String> parts = new ArrayList<>();
        int shown = Math.min(2, view.missing().size());
        for (int index = 0; index < shown; index++) {
            RecipeStockPresenter.Missing missing = view.missing().get(index);
            parts.add(missing.name() + " " + missing.quantity() + " "
                    + AcText.unit(resources, missing.unit(), missing.name()));
        }
        String summary = AcText.join(resources, parts);
        if (view.missing().size() > shown) {
            summary += " " + resources.getString(R.string.ac_stock_more, view.missing().size() - shown);
        }
        return resources.getString(R.string.ac_stock_missing, summary, view.servings());
    }
}
