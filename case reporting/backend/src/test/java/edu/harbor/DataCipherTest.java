package edu.harbor;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class DataCipherTest {
    private final DataCipher cipher = new DataCipher(Base64.getEncoder().encodeToString(new byte[32]));

    @Test
    void encryptsAndDecryptsSensitiveText() {
        String plaintext = "A fictional report for a local test.";
        String encrypted = cipher.encrypt(plaintext);

        assertNotEquals(plaintext, encrypted);
        assertEquals(plaintext, cipher.decrypt(encrypted));
    }

    @Test
    void usesAUniqueNonceForEachEncryption() {
        String first = cipher.encrypt("same text");
        String second = cipher.encrypt("same text");

        assertNotEquals(first, second);
        assertEquals("same text", cipher.decrypt(first));
        assertEquals("same text", cipher.decrypt(second));
    }

    @Test
    void failsClosedWhenCiphertextHasBeenTamperedWith() {
        String encrypted = cipher.encrypt("protected");
        byte[] bytes = Base64.getDecoder().decode(encrypted);
        bytes[bytes.length - 1] ^= 1;

        assertThrows(IllegalStateException.class,
                () -> cipher.decrypt(Base64.getEncoder().encodeToString(bytes)));
    }

    @Test
    void rejectsEncryptionKeysOfTheWrongSize() {
        assertThrows(IllegalStateException.class,
                () -> new DataCipher(Base64.getEncoder().encodeToString(new byte[16])));
    }
}
