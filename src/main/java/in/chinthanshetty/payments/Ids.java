package in.chinthanshetty.payments;

import java.security.SecureRandom;

/** Prefixed random ids (pi_..., re_..., evt_...) so an id says what it refers to. */
public final class Ids {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    private Ids() {}

    public static String next(String prefix) {
        return prefix + "_" + random(20);
    }

    public static String random(int length) {
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(out);
    }
}
