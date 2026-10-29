package in.chinthanshetty.payments.webhooks;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Header format: {@code Webhook-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256 of "t.payload">}.
 * Signing the timestamp with the body lets receivers reject replays of old, captured requests.
 */
public final class WebhookSigner {
    public static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

    private WebhookSigner() {}

    public static String header(String secret, String payload, Instant at) {
        long t = at.getEpochSecond();
        return "t=" + t + ",v1=" + hmac(secret, t + "." + payload);
    }

    /** What a receiver runs. Constant-time comparison; rejects stale timestamps. */
    public static boolean verify(String secret, String payload, String header, Instant now, Duration tolerance) {
        if (header == null) {
            return false;
        }
        Long t = null;
        String v1 = null;
        for (String part : header.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                try {
                    t = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (kv[0].equals("v1")) {
                v1 = kv[1];
            }
        }
        if (t == null || v1 == null) {
            return false;
        }
        if (Duration.between(Instant.ofEpochSecond(t), now).abs().compareTo(tolerance) > 0) {
            return false;
        }
        byte[] expected = hmac(secret, t + "." + payload).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, v1.getBytes(StandardCharsets.US_ASCII));
    }

    static String hmac(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
