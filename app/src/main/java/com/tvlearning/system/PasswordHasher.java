package com.tvlearning.system;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class PasswordHasher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    private PasswordHasher() {
    }

    static String newSalt() {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    static String hash(String value, String salt) {
        PBEKeySpec spec = new PBEKeySpec(value.toCharArray(), Base64.getDecoder().decode(salt), 50000, 256);
        try {
            byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(hash);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("当前系统不支持家长密码保护", e);
        } finally {
            spec.clearPassword();
        }
    }

    static boolean matches(String value, String salt, String expected) {
        return MessageDigest.isEqual(hash(value, salt).getBytes(), expected.getBytes());
    }

    static String newPairingCode() {
        return String.format(Locale.US, "%06d", RANDOM.nextInt(1000000));
    }

    static String newRecoveryCode() {
        StringBuilder result = new StringBuilder("TV-");
        for (int i = 0; i < 12; i++) {
            if (i == 4 || i == 8) {
                result.append('-');
            }
            result.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return result.toString();
    }

    static String newToken() {
        byte[] value = new byte[32];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
