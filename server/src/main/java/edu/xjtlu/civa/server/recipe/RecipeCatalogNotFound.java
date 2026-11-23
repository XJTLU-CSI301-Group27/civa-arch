package edu.xjtlu.civa.server.recipe;

/** Signals a missing catalogue entry; mapped to 404 NOT_FOUND by RecipeCatalogErrors. */
final class RecipeCatalogNotFound extends RuntimeException {
    RecipeCatalogNotFound(String code) {
        super(code);
    }
}
