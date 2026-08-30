package com.zkry.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Encrypts persisted integration credentials with authenticated AES-GCM. */
@Service
public class RuntimeSecretCryptoService {

    private static final String PREFIX = "v1:";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;
    private final boolean productionKeyConfigured;

    public RuntimeSecretCryptoService(
        @Value("${RUNTIME_SETTINGS_ENCRYPTION_KEY:${DB_PASSWORD:voyagemind-local-settings}}") String secret
    ) {
        String normalized = secret == null || secret.isBlank() ? "voyagemind-local-settings" : secret;
        this.productionKeyConfigured = !"voyagemind-local-settings".equals(normalized);
        try {
            this.key = new SecretKeySpec(
                MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8)),
                "AES"
            );
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot initialize runtime-settings encryption", ex);
        }
    }

    public String encrypt(String plainText) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(String.valueOf(plainText).getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(iv) + ":"
                + Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot encrypt runtime setting", ex);
        }
    }

    public String decrypt(String cipherText) {
        try {
            if (cipherText == null || !cipherText.startsWith(PREFIX)) {
                throw new IllegalArgumentException("Unsupported encrypted setting format");
            }
            String[] parts = cipherText.substring(PREFIX.length()).split(":", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0]))
            );
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot decrypt runtime setting", ex);
        }
    }

    public boolean productionKeyConfigured() {
        return productionKeyConfigured;
    }
}
