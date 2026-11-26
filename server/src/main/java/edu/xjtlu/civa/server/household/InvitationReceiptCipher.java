package edu.xjtlu.civa.server.household;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Encrypts retry receipts so the invitation table and database never retain a plaintext invite. */
final class InvitationReceiptCipher {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    InvitationReceiptCipher(Path keyFile) throws IOException {
        Files.createDirectories(keyFile.toAbsolutePath().getParent());
        if (!Files.exists(keyFile)) {
            byte[] bytes = new byte[32]; random.nextBytes(bytes);
            try { Files.write(keyFile, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); }
            catch (java.nio.file.FileAlreadyExistsException anotherServer) { /* Read the existing key, never replace it. */ }
        }
        try {
            Files.setPosixFilePermissions(keyFile.getParent(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            Files.setPosixFilePermissions(keyFile, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException windowsAcl) { /* Inherit the private media directory's Windows ACL. */ }
        byte[] bytes = Files.readAllBytes(keyFile);
        if (bytes.length != 32) throw new IOException("Invalid invitation receipt key; restore it instead of replacing it");
        key = new SecretKeySpec(bytes, "AES");
    }

    String seal(String code) {
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(code.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            byte[] packed = Arrays.copyOf(nonce, nonce.length + encrypted.length);
            System.arraycopy(encrypted, 0, packed, nonce.length, encrypted.length);
            return "sealed-v1:" + Base64.getEncoder().encodeToString(packed);
        } catch (GeneralSecurityException failure) { throw new IllegalStateException("Cannot protect invitation receipt", failure); }
    }

    String open(String stored) {
        try {
            if (stored == null || !stored.startsWith("sealed-v1:")) throw new GeneralSecurityException("Unknown receipt version");
            byte[] packed = Base64.getDecoder().decode(stored.substring(10));
            if (packed.length < 29) throw new GeneralSecurityException("Invalid receipt length");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOf(packed, 12)));
            return new String(cipher.doFinal(Arrays.copyOfRange(packed, 12, packed.length)), java.nio.charset.StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException | IllegalArgumentException failure) { throw new IllegalStateException("Cannot read protected invitation receipt", failure); }
    }
}
