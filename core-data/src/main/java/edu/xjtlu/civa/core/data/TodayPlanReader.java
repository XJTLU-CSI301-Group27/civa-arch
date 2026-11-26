package edu.xjtlu.civa.core.data;

import java.time.LocalDate;
import java.util.List;

/** Reads the personal "today" plan so other features (food diary) can offer its dishes. */
public interface TodayPlanReader {
    List<PlannedDish> plan(LocalDate date);
}
