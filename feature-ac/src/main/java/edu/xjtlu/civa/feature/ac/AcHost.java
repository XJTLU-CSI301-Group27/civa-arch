package edu.xjtlu.civa.feature.ac;

import edu.xjtlu.civa.core.data.ApiCalls;
import edu.xjtlu.civa.core.data.ApiProvider;
import edu.xjtlu.civa.core.data.SessionStore;
import edu.xjtlu.civa.core.data.PlannedDish;
import edu.xjtlu.civa.core.ui.NavHost;
import java.time.LocalDate;
import java.util.List;

/** Implemented by the app shell so the AC feature can read the shared services and navigate. */
public interface AcHost extends NavHost {
    ApiProvider api();

    ApiCalls calls();

    SessionStore sessions();

    /** Dishes planned for the date in the personal "today" plan. */
    List<PlannedDish> planFor(LocalDate date);
}
