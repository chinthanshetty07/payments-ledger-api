package in.chinthanshetty.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PaymentFlowsTest extends ApiTestBase {

    @Test
    void automaticCaptureMovesMoneyAndKeepsTheLedgerBalanced() {
        String id = createIntent(10_000, "automatic").str("id");
        Res confirmed = post(KEY_A, "/v1/payment_intents/" + id + "/confirm", Map.of("payment_method", "pm_card_visa"));
        assertThat(confirmed.status()).isEqualTo(200);
        assertThat(confirmed.str("status")).isEqualTo("succeeded");
        assertThat(confirmed.num("amount_captured")).isEqualTo(10_000);
        // fee = 2.9% of 10,000 (290) + 30 = 320
        assertThat(balanceInr(KEY_A)).isEqualTo(9_680);
        assertThat(ledgerBalanced()).isTrue();
    }

    @Test
    void manualCaptureThenPartialRefundsUpToTheCapturedAmount() {
        String id = createIntent(50_000, "manual").str("id");
        assertThat(post(KEY_A, "/v1/payment_intents/" + id + "/confirm", Map.of("payment_method", "pm_card_visa")).str("status"))
                .isEqualTo("requires_capture");
        assertThat(balanceInr(KEY_A)).isZero(); // an authorization holds funds but moves no money

        Res captured = post(KEY_A, "/v1/payment_intents/" + id + "/capture", Map.of("amount_to_capture", 40_000));
        assertThat(captured.num("amount_captured")).isEqualTo(40_000);
        long afterCapture = balanceInr(KEY_A); // 40,000 - (1,160 + 30)
        assertThat(afterCapture).isEqualTo(38_810);

        assertThat(post(KEY_A, "/v1/refunds", Map.of("payment_intent", id, "amount", 15_000)).status()).isEqualTo(200);
        assertThat(post(KEY_A, "/v1/refunds", Map.of("payment_intent", id, "amount", 25_000)).status()).isEqualTo(200);
        Res tooMuch = post(KEY_A, "/v1/refunds", Map.of("payment_intent", id, "amount", 1));
        assertThat(tooMuch.status()).isEqualTo(400);
        assertThat(tooMuch.str("code")).isEqualTo("charge_already_refunded");

        assertThat(get(KEY_A, "/v1/payment_intents/" + id).num("amount_refunded")).isEqualTo(40_000);
        assertThat(balanceInr(KEY_A)).isEqualTo(afterCapture - 40_000); // fee is not returned
        assertThat(ledgerBalanced()).isTrue();
    }

    @Test
    void overRefundIsRejectedWithoutSideEffects() {
        String id = createIntent(5_000, "automatic").str("id");
        post(KEY_A, "/v1/payment_intents/" + id + "/confirm", Map.of("payment_method", "pm_card_visa"));
        long before = balanceInr(KEY_A);
        Res r = post(KEY_A, "/v1/refunds", Map.of("payment_intent", id, "amount", 5_001));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.str("code")).isEqualTo("amount_too_large");
        assertThat(balanceInr(KEY_A)).isEqualTo(before);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM refunds").query(Long.class).single()).isZero();
    }

    @Test
    void aDeclineIsRecordedAndTheCustomerCanRetryWithAnotherCard() {
        String id = createIntent(2_000, "automatic").str("id");
        Res declined = post(KEY_A, "/v1/payment_intents/" + id + "/confirm", Map.of("payment_method", "pm_card_insufficient_funds"));
        assertThat(declined.status()).isEqualTo(402);
        assertThat(declined.str("code")).isEqualTo("insufficient_funds");
        assertThat(declined.map("payment_intent").get("status")).isEqualTo("requires_payment_method");
        assertThat(balanceInr(KEY_A)).isZero();

        Res retried = post(KEY_A, "/v1/payment_intents/" + id + "/confirm", Map.of("payment_method", "pm_card_rupay"));
        assertThat(retried.str("status")).isEqualTo("succeeded");
        assertThat(retried.body().get("last_payment_error")).isNull();
    }

    @Test
    void invalidTransitionsAreRejected() {
        String id = createIntent(1_000, "manual").str("id");
        assertThat(post(KEY_A, "/v1/payment_intents/" + id + "/capture", null).str("code")).isEqualTo("payment_intent_unexpected_state");
        post(KEY_A, "/v1/payment_intents/" + id + "/confirm", Map.of("payment_method", "pm_card_visa"));
        post(KEY_A, "/v1/payment_intents/" + id + "/capture", null);
        Res cancelAfterCapture = post(KEY_A, "/v1/payment_intents/" + id + "/cancel", null);
        assertThat(cancelAfterCapture.status()).isEqualTo(400);
        Res captureMoreThanAuthorized = post(KEY_A, "/v1/payment_intents/" + createIntentConfirmedManual(1_000) + "/capture",
                Map.of("amount_to_capture", 1_001));
        assertThat(captureMoreThanAuthorized.str("code")).isEqualTo("amount_too_large");
    }

    @Test
    void cancelingAnAuthorizationMovesNoMoney() {
        String id = createIntentConfirmedManual(7_000);
        assertThat(post(KEY_A, "/v1/payment_intents/" + id + "/cancel", null).str("status")).isEqualTo("canceled");
        assertThat(balanceInr(KEY_A)).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ledger_transactions").query(Long.class).single()).isZero();
    }

    @Test
    void merchantsAreIsolatedAndKeysAreRequired() {
        String id = createIntent(1_000, "automatic").str("id");
        assertThat(get(KEY_B, "/v1/payment_intents/" + id).status()).isEqualTo(404);
        assertThat(post(KEY_B, "/v1/payment_intents/" + id + "/confirm", Map.of("payment_method", "pm_card_visa")).status()).isEqualTo(404);
        assertThat(get(null, "/v1/payment_intents/" + id).status()).isEqualTo(401);
        assertThat(get("demo_key_wrong", "/v1/balance").str("code")).isEqualTo("api_key_invalid");
    }

    @Test
    void inputValidation() {
        assertThat(post(KEY_A, "/v1/payment_intents", Map.of("amount", 49, "currency", "inr")).str("code")).isEqualTo("parameter_invalid");
        assertThat(post(KEY_A, "/v1/payment_intents", Map.of("amount", 1_000, "currency", "jpy")).str("code")).isEqualTo("parameter_invalid");
        String id = createIntent(1_000, "automatic").str("id");
        assertThat(post(KEY_A, "/v1/payment_intents/" + id + "/confirm", Map.of("payment_method", "4242424242424242")).str("code"))
                .isEqualTo("payment_method_invalid");
    }

    private String createIntentConfirmedManual(long amount) {
        String id = createIntent(amount, "manual").str("id");
        post(KEY_A, "/v1/payment_intents/" + id + "/confirm", Map.of("payment_method", "pm_card_visa"));
        return id;
    }
}
