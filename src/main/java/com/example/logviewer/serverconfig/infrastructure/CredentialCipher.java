package com.example.logviewer.serverconfig.infrastructure;

import com.example.logviewer.config.AppProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class CredentialCipher {

    private static final String PREFIX = "ENC(";
    private static final String SUFFIX = ")";
    private final byte[] key;
    private final SecureRandom secureRandom = new SecureRandom();

    public CredentialCipher(AppProperties properties) {
        this.key = sha256(properties.getConfigSecret() == null ? "dev-secret" : properties.getConfigSecret());
    }

    public String encryptIfNeeded(String plain) {
        if (isBlank(plain) || isEncrypted(plain)) {
            return plain;
        }
        try {
            byte[] iv = new byte[12];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, 0, 16, "AES"), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length);
            buffer.put(iv).put(encrypted);
            return PREFIX + Base64.getEncoder().encodeToString(buffer.array()) + SUFFIX;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt credential", e);
        }
    }

    public String decryptIfEncrypted(String cipherText) {
        if (!isEncrypted(cipherText)) {
            return cipherText;
        }
        try {
            String raw = cipherText.substring(PREFIX.length(), cipherText.length() - SUFFIX.length());
            byte[] data = Base64.getDecoder().decode(raw);
            ByteBuffer buffer = ByteBuffer.wrap(data);
            byte[] iv = new byte[12];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, 0, 16, "AES"), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt credential", e);
        }
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX) && value.endsWith(SUFFIX);
    }

    public String mask(String value) {
        if (isBlank(value)) {
            return null;
        }
        return "******";
    }

    private boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }

    private byte[] sha256(String v) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
