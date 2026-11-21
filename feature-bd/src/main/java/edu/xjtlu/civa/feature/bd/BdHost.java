package edu.xjtlu.civa.feature.bd;

import edu.xjtlu.civa.core.data.SessionStore;

/** Implemented by the application host so the home screen can scope personal drafts. */
public interface BdHost {
    SessionStore sessions();
}