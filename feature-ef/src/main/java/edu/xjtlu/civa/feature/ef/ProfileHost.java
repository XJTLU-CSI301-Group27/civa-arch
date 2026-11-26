package edu.xjtlu.civa.feature.ef;

import edu.xjtlu.civa.core.data.ApiCalls;
import edu.xjtlu.civa.core.data.ApiProvider;
import edu.xjtlu.civa.core.data.AuthRepository;
import edu.xjtlu.civa.core.data.SessionStore;
import edu.xjtlu.civa.core.ui.NavHost;

/** Implemented by the app shell so the profile screen can read the session and sign out. */
public interface ProfileHost extends NavHost {
    SessionStore sessions();

    AuthRepository authRepository();

    ApiProvider api();

    ApiCalls calls();
}
