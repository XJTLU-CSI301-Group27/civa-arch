package edu.xjtlu.civa.feature.ac;

import edu.xjtlu.civa.core.data.ApiCalls;
import edu.xjtlu.civa.core.data.ApiProvider;
import edu.xjtlu.civa.core.ui.NavHost;

/** Implemented by the app shell so the AC feature can read the shared services and navigate. */
public interface AcHost extends NavHost {
    ApiProvider api();

    ApiCalls calls();
}
