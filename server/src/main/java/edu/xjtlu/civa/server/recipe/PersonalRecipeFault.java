package edu.xjtlu.civa.server.recipe;

/** Domain failure for personal recipe writes; mapped by RecipeWriteErrors. */
final class PersonalRecipeFault extends RuntimeException {
    final String code;

    PersonalRecipeFault(String code) {
        super(code);
        this.code = code;
    }
}
