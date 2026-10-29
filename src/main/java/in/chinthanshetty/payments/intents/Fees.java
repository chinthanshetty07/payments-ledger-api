package in.chinthanshetty.payments.intents;

/** An illustrative fee rule (2.9% + 30 minor units, half-up), not any real provider's pricing. */
public final class Fees {
    private Fees() {}

    public static long forCapture(long amount) {
        long percentage = (amount * 29 + 500) / 1000; // integer half-up rounding of amount * 2.9%
        return Math.min(amount, percentage + 30);
    }
}
