package edu.xjtlu.civa.server.media;

/** Media validation/authorization fault mapped to an HTTP error by MediaErrors. */
final class MediaFault extends RuntimeException {
    final String code;

    MediaFault(String code) {
        super(code);
        this.code = code;
    }
}
