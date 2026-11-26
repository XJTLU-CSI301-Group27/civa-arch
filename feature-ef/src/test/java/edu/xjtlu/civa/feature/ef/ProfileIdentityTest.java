package edu.xjtlu.civa.feature.ef;

import static org.junit.Assert.*;
import org.junit.Test;

public class ProfileIdentityTest {
    @Test public void accountScopeIsStableAndContainsNoAccountText() {
        String scope=ProfileIdentity.scope("LYFFAY-account-id");
        assertEquals(scope,ProfileIdentity.scope("LYFFAY-account-id"));
        assertTrue(scope.matches("[a-f0-9]{64}")); assertFalse(scope.contains("LYFFAY"));
    }
    @Test public void differentAccountsHaveDifferentPrivateDirectories() { assertNotEquals(ProfileIdentity.scope("account-A"),ProfileIdentity.scope("account-B")); }
    @Test(expected=IllegalArgumentException.class) public void missingAccountCannotWriteAProfile() { ProfileIdentity.scope(""); }
    @Test public void trimsDisplayNameWithoutChangingItsCase() { assertEquals("LYFFAY",ProfileIdentity.normalizedName("  LYFFAY  ")); }
    @Test(expected=IllegalArgumentException.class) public void emptyDisplayNameIsRejected() { ProfileIdentity.normalizedName("   "); }
    @Test public void limitsAreInUnicodeCodePoints() { assertEquals(120,ProfileIdentity.normalizedName("😺".repeat(60)).length()); }
    @Test(expected=IllegalArgumentException.class) public void tooLongDisplayNameIsRejected() { ProfileIdentity.normalizedName("猫".repeat(61)); }
    @Test public void avatarReferenceCannotEscapeTheAccountsDirectory() {
        assertTrue(ProfileIdentity.avatarFile("avatar-5d41-eeee.jpg"));
        for(String reference:new String[]{"../avatar-abcd.jpg","/tmp/avatar-abcd.jpg","https://example.com/avatar-abcd.jpg","avatar-abcd.png","avatar-../a.jpg"}) assertFalse(ProfileIdentity.avatarFile(reference));
    }
}
