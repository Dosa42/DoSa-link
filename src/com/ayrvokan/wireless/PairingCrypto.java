package com.ayrvokan.wireless;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class PairingCrypto {
    private final SecretKeySpec key;
    private final IvParameterSpec iv;

    public PairingCrypto(String hexadecimal, String initializationVector) {
        if (hexadecimal == null || !hexadecimal.matches("(?i)([0-9a-f]{32}|[0-9a-f]{48}|[0-9a-f]{64})")) throw new IllegalArgumentException("Device metadata needs a hexadecimal AES secretKey");
        byte[] bytes = new byte[hexadecimal.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(hexadecimal.substring(i * 2, i * 2 + 2), 16);
        byte[] vector = initializationVector.getBytes(StandardCharsets.UTF_8);
        if (vector.length != 16) throw new IllegalArgumentException("Device metadata IV must contain 16 UTF-8 bytes");
        key = new SecretKeySpec(bytes, "AES"); iv = new IvParameterSpec(vector);
    }

    public String encrypt(String text) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key, iv);
        return Base64.getEncoder().encodeToString(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }

    public String decrypt(String text) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, key, iv);
        return new String(cipher.doFinal(Base64.getDecoder().decode(text)), StandardCharsets.UTF_8);
    }
}
