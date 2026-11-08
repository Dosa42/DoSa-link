import com.ayrvokan.wireless.PairingCrypto;

public final class VerifyPairingCrypto {
    public static void main(String[] args) throws Exception {
        PairingCrypto crypto = new PairingCrypto("000102030405060708090a0b0c0d0e0f", "0000000000000000");
        String plaintext = "NSPanel Wi-Fi π";
        String expected = "h1S4OwWmUeiKEHGg+ssKWsK7SMD9KASg3lIfbTVDHos=";
        if (!expected.equals(crypto.encrypt(plaintext))) throw new AssertionError("CBC/PKCS7/Base64 differs from OpenSSL fixture");
        if (!plaintext.equals(crypto.decrypt(expected))) throw new AssertionError("Ciphertext could not be decoded as UTF-8");
        try { new PairingCrypto("invalid", "0000000000000000"); throw new AssertionError("Invalid device key accepted"); }
        catch (IllegalArgumentException expectedFailure) { }
        try { new PairingCrypto("000102030405060708090a0b0c0d0e0f", "short"); throw new AssertionError("Invalid IV accepted"); }
        catch (IllegalArgumentException expectedFailure) { }
        System.out.println("Pairing encryption fixture passed");
    }
}
