package edu.xjtlu.civa.server.plan;

/** Planning write fault mapped to an HTTP error by PlansErrors. */
final class PlanFault extends RuntimeException {
    final String code;

    PlanFault(String code) {
        super(code);
        this.code = code;
    }
}
