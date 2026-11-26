package edu.xjtlu.civa.server.profile;

/** Preview/preference write fault mapped to an HTTP error by PreferencesErrors. */
final class PreferencesFault extends RuntimeException {
    final String code;

    PreferencesFault(String code) {
        super(code);
        this.code = code;
    }
}
