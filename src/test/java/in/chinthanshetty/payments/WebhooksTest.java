package in.chinthanshetty.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import in.chinthanshetty.payments.webhooks.WebhookDispatcher;
import in.chinthanshetty.payments.webhooks.WebhookSigner;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class WebhooksTest extends ApiTestBase {
    record Received(String body, String signature, String id) {}

    @Autowired
    WebhookDispatcher dispatcher;

    HttpServer receiver;
    final List<Received> received = new CopyOnWriteArrayList<>();
    final AtomicInteger failuresLeft = new AtomicInteger();

    @BeforeEach
    void startReceiver() throws Exception {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/hook", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int status = failuresLeft.getAndDecrement() > 0 ? 500 : 200;
            if (status == 200) {
                received.add(new Received(body, ex.getRequestHeaders().getFirst("Webhook-Signature"), ex.getRequestHeaders().getFirst("Webhook-Id")));
            }
            ex.sendResponseHeaders(status, -1);
            ex.close();
        });
        receiver.start();
    }

    @AfterEach
    void stopReceiver() {
        receiver.stop(0);
    }

    private String register() {
        String url = "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook";
        return (String) post(KEY_A, "/v1/webhook_endpoints", Map.of("url", url)).body().get("secret");
    }

    @Test
    void eventsAreSignedAndRetriedUntilDelivered() throws Exception {
        String secret = register();
        failuresLeft.set(2);
        String id = createIntent(5_000, "automatic").str("id");
        post(KEY_A, "/v1/payment_intents/" + id + "/confirm", Map.of("payment_method", "pm_card_visa"));

        for (int i = 0; i < 3; i++) {
            // Real backoff is configured to 0 in tests; release the claim lease to simulate time passing.
            jdbc.sql("UPDATE webhook_deliveries SET next_attempt_at = now() WHERE status = 'pending'").update();
            dispatcher.dispatchDue();
        }

        assertThat(received).hasSize(1);
        Received r = received.get(0);
        assertThat(json.readTree(r.body()).get("type").asText()).isEqualTo("payment_intent.succeeded");
        assertThat(WebhookSigner.verify(secret, r.body(), r.signature(), Instant.now(), WebhookSigner.DEFAULT_TOLERANCE)).isTrue();
        assertThat(WebhookSigner.verify(secret, r.body().replace("5000", "9000"), r.signature(), Instant.now(), WebhookSigner.DEFAULT_TOLERANCE)).isFalse();
        assertThat(WebhookSigner.verify("whsec_wrong", r.body(), r.signature(), Instant.now(), WebhookSigner.DEFAULT_TOLERANCE)).isFalse();
        assertThat(jdbc.sql("SELECT attempts FROM webhook_deliveries").query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    void aDeliveryIsMarkedFailedAfterMaxAttempts() {
        register();
        failuresLeft.set(100);
        String id = createIntent(5_000, "automatic").str("id");
        post(KEY_A, "/v1/payment_intents/" + id + "/cancel", null);
        for (int i = 0; i < 6; i++) {
            jdbc.sql("UPDATE webhook_deliveries SET next_attempt_at = now() WHERE status = 'pending'").update();
            dispatcher.dispatchDue();
        }
        assertThat(jdbc.sql("SELECT status FROM webhook_deliveries").query(String.class).single()).isEqualTo("failed");
        assertThat(jdbc.sql("SELECT attempts FROM webhook_deliveries").query(Integer.class).single()).isEqualTo(4); // max-attempts in tests
    }

    @Test
    void aClaimedDeliveryIsNotHandedToASecondWorkerWhileLeased() {
        register();
        String id = createIntent(5_000, "automatic").str("id");
        post(KEY_A, "/v1/payment_intents/" + id + "/cancel", null);
        assertThat(dispatcher.claim(20)).hasSize(1);   // worker 1 claims it (lease pushes next_attempt_at forward)
        assertThat(dispatcher.claim(20)).isEmpty();    // worker 2 finds nothing due
        assertThat(dispatcher.dispatchDue()).isZero();
    }

    @Test
    void noEventIsWrittenWhenTheStateChangeFails() {
        register();
        String id = createIntent(1_000, "manual").str("id");
        post(KEY_A, "/v1/payment_intents/" + id + "/capture", null); // invalid: not confirmed
        assertThat(jdbc.sql("SELECT COUNT(*) FROM events").query(Long.class).single()).isZero();
    }

    @Test
    void staleOrMalformedSignaturesAreRejected() {
        String header = WebhookSigner.header("whsec_x", "{}", Instant.now().minus(Duration.ofMinutes(10)));
        assertThat(WebhookSigner.verify("whsec_x", "{}", header, Instant.now(), WebhookSigner.DEFAULT_TOLERANCE)).isFalse();
        assertThat(WebhookSigner.verify("whsec_x", "{}", "t=abc,v1=00", Instant.now(), WebhookSigner.DEFAULT_TOLERANCE)).isFalse();
        assertThat(WebhookSigner.verify("whsec_x", "{}", null, Instant.now(), WebhookSigner.DEFAULT_TOLERANCE)).isFalse();
    }
}
