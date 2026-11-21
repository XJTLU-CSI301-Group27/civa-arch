package edu.xjtlu.civa.feature.bd;

import edu.xjtlu.civa.core.data.ApiCalls;
import edu.xjtlu.civa.core.data.ApiProvider;
import edu.xjtlu.civa.core.data.SessionStore;

/** Implemented by the application host: session for draft scoping, plus the shared call runner. */
public interface BdHost {
    SessionStore sessions();

    ApiProvider api();

    ApiCalls calls();
}
