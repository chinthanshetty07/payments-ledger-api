package in.chinthanshetty.payments.intents;

import java.time.Instant;

public record PaymentIntent(
        String id,
        String merchantId,
        long amount,
        String currency,
        String captureMethod,
        String status,
        long amountCaptured,
        long amountRefunded,
        String lastErrorCode,
        int version,
        Instant createdAt,
        Instant updatedAt) {

    public static final String REQUIRES_CONFIRMATION = "requires_confirmation";
    public static final String REQUIRES_PAYMENT_METHOD = "requires_payment_method";
    public static final String REQUIRES_CAPTURE = "requires_capture";
    public static final String SUCCEEDED = "succeeded";
    public static final String CANCELED = "canceled";

    public long refundable() {
        return amountCaptured - amountRefunded;
    }
}
