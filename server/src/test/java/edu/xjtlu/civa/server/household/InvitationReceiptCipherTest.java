package edu.xjtlu.civa.server.household;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class InvitationReceiptCipherTest {
    @TempDir Path root;
    @Test void protectedReceiptSurvivesRestartWithoutStoringPlainCode() throws Exception {
        Path key=root.resolve("private/receipt.key");
        InvitationReceiptCipher first=new InvitationReceiptCipher(key);
        String code="AAAAAAAAAAAAAAAAAAAAAAAAAA",sealed=first.seal(code);
        assertFalse(sealed.contains(code));assertEquals(32,Files.readAllBytes(key).length);
        assertEquals(code,new InvitationReceiptCipher(key).open(sealed));
        assertNotEquals(sealed,first.seal(code));
    }
    @Test void wrongKeyOrDamagedReceiptFailsClosed() throws Exception {
        InvitationReceiptCipher first=new InvitationReceiptCipher(root.resolve("one.key"));
        InvitationReceiptCipher second=new InvitationReceiptCipher(root.resolve("two.key"));
        String sealed=first.seal("AAAAAAAAAAAAAAAAAAAAAAAAAA");
        assertThrows(IllegalStateException.class,()->second.open(sealed));
        assertThrows(IllegalStateException.class,()->first.open("plaintext"));
        assertThrows(IllegalStateException.class,()->first.open("sealed-v1:!"));
    }
    @Test void corruptKeyIsNotSilentlyReplaced() throws Exception {
        Path key=root.resolve("broken.key");Files.write(key,new byte[]{1,2});
        assertThrows(java.io.IOException.class,()->new InvitationReceiptCipher(key));
        assertArrayEquals(new byte[]{1,2},Files.readAllBytes(key));
    }
}
