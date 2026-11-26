package edu.xjtlu.civa.server.record;

/** Business fault for the personal food diary; mapped to HTTP statuses by RecordsErrors. */
final class RecordFault extends RuntimeException {
    final String code;

    RecordFault(String code) {
        super(code);
        this.code = code;
    }
}
