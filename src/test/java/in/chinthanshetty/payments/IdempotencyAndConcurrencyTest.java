package in.chinthanshetty.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class IdempotencyAndConcurrencyTest extends ApiTestBase {

    @Test
    void aRetryWithTheSameKeyReplaysInsteadOfChargingTwice() {
        String id = createIntent(10_000, "automatic").str("id");
        Map<String, Object> body = Map.of("payment_method", "pm_card_visa");
        Res first = post(KEY_A, "/v1/payment_intents/" + id + "/confirm", body, "confirm-1");
        Res retry = post(KEY_A, "/v1/payment_intents/" + id + "/confirm", body, "confirm-1");

        assertThat(first.status()).isEqualTo(200);
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.raw().headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ledger_transactions").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void reusingAKeyForADifferentRequestIsAnError() {
        post(KEY_A, "/v1/payment_intents", Map.of("amount", 1_000, "currency", "inr"), "create-1");
        Res reused = post(KEY_A, "/v1/payment_intents", Map.of("amount", 2_000, "currency", "inr"), "create-1");
        assertThat(reused.status()).isEqualTo(422);
        assertThat(reused.str("code")).isEqualTo("idempotency_key_reused");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM payment_intents").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void anErrorIsReplayedToo() {
        String id = createIntent(1_000, "automatic").str("id");
        Res declined = post(KEY_A, "/v1/payment_intents/" + id + "/confirm", Map.of("payment_method", "pm_card_declined"), "c-1");
        // Even though a good card would now work, the same key returns the same decline.
        Res replay = post(KEY_A, "/v1/payment_intents/" + id + "/confirm", Map.of("payment_method", "pm_card_declined"), "c-1");
        assertThat(replay.status()).isEqualTo(402);
        assertThat(replay.body()).isEqualTo(declined.body());
    }

    @Test
    void keysAreScopedPerMerchant() {
        Res a = post(KEY_A, "/v1/payment_intents", Map.of("amount", 1_000, "currency", "inr"), "same-key");
        Res b = post(KEY_B, "/v1/payment_intents", Map.of("amount", 1_000, "currency", "inr"), "same-key");
        assertThat(a.str("id")).isNotEqualTo(b.str("id"));
    }

    @Test
    void parallelCapturesOfOnePaymentSucceedExactlyOnce() throws Exception {
        String id = createIntent(20_000, "manual").str("id");
        post(KEY_A, "/v1/payment_intents/" + id + "/confirm", Map.of("payment_method", "pm_card_visa"));

        List<Res> results = race(8, i -> post(KEY_A, "/v1/payment_intents/" + id + "/capture", null, "capture-" + i));

        assertThat(results).filteredOn(r -> r.status() == 200).hasSize(1);
        assertThat(results).filteredOn(r -> r.status() != 200)
                .allSatisfy(r -> assertThat(r.str("code")).isIn("concurrent_update", "payment_intent_unexpected_state"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ledger_transactions WHERE type = 'capture'").query(Long.class).single()).isEqualTo(1);
        assertThat(ledgerBalanced()).isTrue();
    }

    @Test
    void parallelRequestsWithOneKeyCreateOnePayment() throws Exception {
        Map<String, Object> body = Map.of("amount", 3_000, "currency", "inr");
        List<Res> results = race(8, i -> post(KEY_A, "/v1/payment_intents", body, "burst-key"));

        assertThat(jdbc.sql("SELECT COUNT(*) FROM payment_intents").query(Long.class).single()).isEqualTo(1);
        // Every caller either created it, got the replay, or was told to retry (409 while in flight).
        assertThat(results).allSatisfy(r -> assertThat(r.status()).isIn(200, 409));
        assertThat(results.stream().filter(r -> r.status() == 200).map(r -> r.str("id")).distinct()).hasSize(1);
    }

    @Test
    void parallelRefundsNeverExceedTheCapturedAmount() throws Exception {
        String id = createIntent(10_000, "automatic").str("id");
        post(KEY_A, "/v1/payment_intents/" + id + "/confirm", Map.of("payment_method", "pm_card_visa"));

        race(10, i -> post(KEY_A, "/v1/refunds", Map.of("payment_intent", id, "amount", 3_000), "refund-" + i));

        long refunded = jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM refunds").query(Long.class).single();
        assertThat(refunded).isLessThanOrEqualTo(10_000);
        assertThat(get(KEY_A, "/v1/payment_intents/" + id).num("amount_refunded")).isEqualTo(refunded);
        assertThat(ledgerBalanced()).isTrue();
    }

    interface Call {
        Res apply(int i);
    }

    private List<Res> race(int n, Call call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Res>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            Callable<Res> task = () -> {
                start.await();
                return call.apply(idx);
            };
            futures.add(pool.submit(task));
        }
        start.countDown();
        List<Res> out = new ArrayList<>();
        for (Future<Res> f : futures) {
            out.add(f.get());
        }
        pool.shutdown();
        return out;
    }
}
