package com.ganzi.emori.auth;

import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class PasswordHasher {
    private static final int ITERATIONS = 210_000;
    private static final int KEY_BITS = 256;
    private final SecureRandom random = new SecureRandom();

    public String hash(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] derived = derive(password, salt, ITERATIONS);
        return "pbkdf2-sha256$" + ITERATIONS + "$" + Base64.getUrlEncoder().withoutPadding().encodeToString(salt)
                + "$" + Base64.getUrlEncoder().withoutPadding().encodeToString(derived);
    }

    public boolean matches(String password, String stored) {
        try {
            String[] fields = stored.split("\\$");
            if (fields.length != 4 || !"pbkdf2-sha256".equals(fields[0])) return false;
            int iterations = Integer.parseInt(fields[1]);
            if (iterations < 1 || iterations > 1_000_000) return false;
            byte[] salt = Base64.getUrlDecoder().decode(fields[2]);
            byte[] expected = Base64.getUrlDecoder().decode(fields[3]);
            return salt.length == 16 && expected.length == KEY_BITS / 8
                    && MessageDigest.isEqual(expected, derive(password, salt, iterations));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Password hashing unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }
}
