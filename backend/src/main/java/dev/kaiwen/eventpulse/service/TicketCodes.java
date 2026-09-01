package dev.kaiwen.eventpulse.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class TicketCodes {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TicketCodes() {
    }

    public static String raw() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }
        catch (Exception e) {
            throw new IllegalStateException("无法计算核销码哈希", e);
        }
    }

    public static String encrypt(String raw, String secret) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(secret), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(raw.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(packed);
        }
        catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法加密核销码", e);
        }
    }

    public static String decrypt(String cipherText, String secret) {
        try {
            byte[] packed = Base64.getDecoder().decode(cipherText);
            byte[] iv = new byte[12];
            System.arraycopy(packed, 0, iv, 0, 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(secret), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(packed, 12, packed.length - 12), StandardCharsets.UTF_8);
        }
        catch (Exception e) {
            throw new IllegalStateException("无法解密核销码", e);
        }
    }

    private static SecretKeySpec key(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        }
        catch (Exception e) {
            throw new IllegalStateException("无法派生加密密钥", e);
        }
    }
}
