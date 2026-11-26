package edu.xjtlu.civa.server.web;

/** Marker for contract operations that are still explicit 501 placeholders. */
public final class NotImplementedException extends RuntimeException {
    public NotImplementedException() {
        super("Contract operation not implemented");
    }
}
