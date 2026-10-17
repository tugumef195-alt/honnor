package edu.harbor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class DataCipher {
    private static final int NONCE_LENGTH = 12;
    private final byte[] keyBytes;
    private final SecureRandom random = new SecureRandom();

    public DataCipher(@Value("${app.encryption-key}") String encodedKey) {
        try {
            keyBytes = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("APP_ENCRYPTION_KEY must be base64-encoded 32-byte key material");
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException("APP_ENCRYPTION_KEY must decode to exactly 32 bytes");
        }
    }

    public void validateConfiguration() {
        encrypt("configuration-check");
    }

    public String encrypt(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            byte[] nonce = new byte[NONCE_LENGTH];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(128, nonce));
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(ciphertext, 0, combined, nonce.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to protect sensitive information");
        }
    }

    public String decrypt(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            byte[] combined = Base64.getDecoder().decode(value);
            byte[] nonce = new byte[NONCE_LENGTH];
            System.arraycopy(combined, 0, nonce, 0, nonce.length);
            byte[] ciphertext = new byte[combined.length - nonce.length];
            System.arraycopy(combined, nonce.length, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read protected case information");
        }
    }
}
