package in.chinthanshetty.payments.intents;

import org.springframework.stereotype.Component;

/** A simulated card network. Test payment-method tokens choose the outcome; no real card data exists here. */
@Component
public class CardNetwork {
    public record Authorization(boolean approved, String declineCode) {}

    public Authorization authorize(String paymentMethod, long amount, String currency) {
        return switch (paymentMethod) {
            case "pm_card_visa", "pm_card_mastercard", "pm_card_rupay" -> new Authorization(true, null);
            case "pm_card_declined" -> new Authorization(false, "card_declined");
            case "pm_card_insufficient_funds" -> new Authorization(false, "insufficient_funds");
            case "pm_card_expired" -> new Authorization(false, "expired_card");
            default -> throw new IllegalArgumentException("unknown payment method");
        };
    }
}
